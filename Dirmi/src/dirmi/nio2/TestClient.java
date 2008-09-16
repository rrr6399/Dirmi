/*
 *  Copyright 2008 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dirmi.nio2;

import java.io.IOException;

import java.nio.ByteBuffer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.joda.time.DateTime;

import dirmi.core.ThreadPool;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestClient implements MessageReceiver {
    public static void main(String[] args) throws Exception {
        SocketAddress address = new InetSocketAddress(args[0], Integer.parseInt(args[1]));
        ThreadPool pool = new ThreadPool(100, true, "dirmi");
        SocketMessageProcessor processor = new SocketMessageProcessor(pool);
        MessageConnector connector = processor.newConnector(address);
        System.out.println(connector);

        MessageChannel channel = connector.connect();
        System.out.println(channel);
        channel.receive(new TestClient());

        int count = 0;
        while (true) {
            byte[] message = ("" + count + "/hello " + new DateTime() + "@" + count).getBytes();
            channel.send(ByteBuffer.wrap(message));
            count++;
            System.out.println("sent message " + count);
            Thread.sleep(10);
            /*
            if (count > 2) {
                con.close();
            }
            */
        }
    }

    private TestClient() {
    }

    public MessageReceiver receive(int totalSize, int offset, ByteBuffer buffer) {
        return this;//new TestClient();
    }

    public void process() {
    }

    public void closed() {
    }

    public void closed(IOException e) {
        /*
        System.out.println("Closed");
        e.printStackTrace(System.out);
        */
    }
}
