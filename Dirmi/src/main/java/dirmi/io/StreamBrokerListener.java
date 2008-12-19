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

package dirmi.io;

import java.io.IOException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface StreamBrokerListener {
    /**
     * Called at most once as soon as broker has been established. This
     * method may safely block, and it can interact with the broker too.
     */
    void established(StreamBroker broker);

    /**
     * Called when broker cannot be established. This method may safely
     * block. Invocation of this method does not imply that new brokers cannot
     * be established. If established method re-adds this listener, then the
     * failed method must do so as well.
     */
    void failed(IOException e);
}