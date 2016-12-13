/*
 * Copyright 2016 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package enmasse.systemtest;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class QueueTest extends VertxTestBase {
    @Test
    public void testQueue() throws Exception {
        Destination dest = Destination.queue("myqueue");
        deploy(dest);
        EnMasseClient client = createQueueClient();
        List<String> msgs = generateMessages(1024);

        Future<Integer> numSent = client.sendMessages(dest.getAddress(), msgs);
        assertThat(numSent.get(1, TimeUnit.MINUTES), is(msgs.size()));

        Future<List<String>> received = client.recvMessages(dest.getAddress(), msgs.size());
        assertThat(received.get(1, TimeUnit.MINUTES).size(), is(msgs.size()));
    }

    private List<String> generateMessages(int numMessages) {
        return IntStream.range(0, numMessages)
                .mapToObj(num -> "msg" + num)
                .collect(Collectors.toList());
    }

    @Test
    public void testScaledown() throws Exception {
        Destination dest = Destination.queue("myqueue");
        deploy(dest);
        scale(dest, 1);
        EnMasseClient client = createQueueClient();
        List<Future<Integer>> sent = Arrays.asList(
                client.sendMessages(dest.getAddress(), Arrays.asList("foo")),
                client.sendMessages(dest.getAddress(), Arrays.asList("bar")),
                client.sendMessages(dest.getAddress(), Arrays.asList("baz")),
                client.sendMessages(dest.getAddress(), Arrays.asList("quux")));

        assertThat(sent.get(0).get(1, TimeUnit.MINUTES), is(1));
        assertThat(sent.get(1).get(1, TimeUnit.MINUTES), is(1));
        assertThat(sent.get(2).get(1, TimeUnit.MINUTES), is(1));
        assertThat(sent.get(3).get(1, TimeUnit.MINUTES), is(1));

        scale(dest, 1);

        Future<List<String>> received = client.recvMessages(dest.getAddress(), 4);

        assertThat(received.get(1, TimeUnit.MINUTES).size(), is(4));
    }
}

