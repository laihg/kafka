/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.consumer;

import org.apache.kafka.clients.consumer.internals.AbstractPartitionAssignor;
import org.apache.kafka.common.TopicPartition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>The range assignor works on a per-topic basis. For each topic, we lay out the available partitions in numeric order
 * and the consumers in lexicographic order. We then divide the number of partitions by the total number of
 * consumers to determine the number of partitions to assign to each consumer. If it does not evenly
 * divide, then the first few consumers will have one extra partition.
 *
 * <p>For example, suppose there are two consumers <code>C0</code> and <code>C1</code>, two topics <code>t0</code> and
 * <code>t1</code>, and each topic has 3 partitions, resulting in partitions <code>t0p0</code>, <code>t0p1</code>,
 * <code>t0p2</code>, <code>t1p0</code>, <code>t1p1</code>, and <code>t1p2</code>.
 *
 * <p>The assignment will be:
 * <ul>
 * <li><code>C0: [t0p0, t0p1, t1p0, t1p1]</code></li>
 * <li><code>C1: [t0p2, t1p2]</code></li>
 * </ul>
 *
 * Since the introduction of static membership, we could leverage <code>group.instance.id</code> to make the assignment behavior more sticky.
 * For the above example, after one rolling bounce, group coordinator will attempt to assign new <code>member.id</code> towards consumers,
 * for example <code>C0</code> -&gt; <code>C3</code> <code>C1</code> -&gt; <code>C2</code>.
 *
 * <p>The assignment could be completely shuffled to:
 * <ul>
 * <li><code>C3 (was C0): [t0p2, t1p2] (before was [t0p0, t0p1, t1p0, t1p1])</code>
 * <li><code>C2 (was C1): [t0p0, t0p1, t1p0, t1p1] (before was [t0p2, t1p2])</code>
 * </ul>
 *
 * The assignment change was caused by the change of <code>member.id</code> relative order, and
 * can be avoided by setting the group.instance.id.
 * Consumers will have individual instance ids <code>I1</code>, <code>I2</code>. As long as
 * 1. Number of members remain the same across generation
 * 2. Static members' identities persist across generation
 * 3. Subscription pattern doesn't change for any member
 *
 * <p>The assignment will always be:
 * <ul>
 * <li><code>I0: [t0p0, t0p1, t1p0, t1p1]</code>
 * <li><code>I1: [t0p2, t1p2]</code>
 * </ul>
 */
public class RangeAssignor extends AbstractPartitionAssignor {
    public static final String RANGE_ASSIGNOR_NAME = "range";

    @Override
    public String name() {
        return RANGE_ASSIGNOR_NAME;
    }

    /**
     * 获取topic被哪些consumer订阅了
     * @param consumerMetadata 消费者元数据，key：消费者成员ID，value：订阅了哪些topic
     * @return key:topic名称，value:消费者信息
     */
    private Map<String, List<MemberInfo>> consumersPerTopic(Map<String, Subscription> consumerMetadata) {
        Map<String, List<MemberInfo>> topicToConsumers = new HashMap<>();
        for (Map.Entry<String, Subscription> subscriptionEntry : consumerMetadata.entrySet()) {
            String consumerId = subscriptionEntry.getKey();
            MemberInfo memberInfo = new MemberInfo(consumerId, subscriptionEntry.getValue().groupInstanceId());
            for (String topic : subscriptionEntry.getValue().topics()) {
                put(topicToConsumers, topic, memberInfo);
            }
        }
        return topicToConsumers;
    }

    /**
     * 对消费者订阅的topic所要消费的partition进行分配
     * @param partitionsPerTopic The number of partitions for each subscribed topic. Topics not in metadata will be excluded
     *                           from this map. key：topic名称，value：topic partition数量
     * @param subscriptions Map from the member id to their respective topic subscription key：消费者成员ID，value：订阅了哪些topic
     * @return key：消费者成员ID，value：可以消费的partition
     */
    @Override
    public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic,
                                                    Map<String, Subscription> subscriptions) {
        //获取topic被哪些consumer订阅了
        Map<String, List<MemberInfo>> consumersPerTopic = consumersPerTopic(subscriptions);

        Map<String, List<TopicPartition>> assignment = new HashMap<>();
        for (String memberId : subscriptions.keySet())
            assignment.put(memberId, new ArrayList<>());

        for (Map.Entry<String, List<MemberInfo>> topicEntry : consumersPerTopic.entrySet()) {
            String topic = topicEntry.getKey(); //topic
            List<MemberInfo> consumersForTopic = topicEntry.getValue(); //订阅topic的消费者成员
            //根据topic获取分区数量
            Integer numPartitionsForTopic = partitionsPerTopic.get(topic);
            if (numPartitionsForTopic == null)
                continue;

            Collections.sort(consumersForTopic);
            //平均每个消费者可以消费的分区,比如分区数量是3，消费者成员数量是2，则每个消费者可消费到1个partition
            int numPartitionsPerConsumer = numPartitionsForTopic / consumersForTopic.size();

            //消费者多余的消费分区，比如分区数量是3，消费者成员数量是2，则多出一个分区
            int consumersWithExtraPartition = numPartitionsForTopic % consumersForTopic.size();

            //封装topic partition信息
            List<TopicPartition> partitions = AbstractPartitionAssignor.partitions(topic, numPartitionsForTopic);
            for (int i = 0, n = consumersForTopic.size(); i < n; i++) {
                //假设分区数量是3，消费者成员数量是2
                //first sub: start=0, length = 1 + (0 + 1 > 1 ? 0 : 1) = 1, sub(0,2) , partitions= 0,1
                //second sub: start=1, length = 1 + (1 + 1 > 1 ? 0 : 1) = 2, sub(2,3) , partitions= 2

                //假设分区数量是5，消费者成员数量是2
                //start = 2*0+0=0, length = 2 + (1 > 1 ? 0 : 1)=3, sub(0,3), partitions= 0,1,2
                //start = 2*1+1, length = 2 + 0, sub(3,5), partitions=3,4
                int start = numPartitionsPerConsumer * i + Math.min(i, consumersWithExtraPartition);
                int length = numPartitionsPerConsumer + (i + 1 > consumersWithExtraPartition ? 0 : 1);
                //消费者可以消费的partition
                assignment.get(consumersForTopic.get(i).memberId).addAll(partitions.subList(start, start + length));
            }
        }
        return assignment;
    }
}
