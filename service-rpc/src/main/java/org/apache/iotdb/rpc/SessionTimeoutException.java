/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.rpc;

import org.apache.iotdb.service.rpc.thrift.TSStatus;

public class SessionTimeoutException extends StatementExecutionException {
  public SessionTimeoutException(TSStatus status) {
    super(status);
  }

  public SessionTimeoutException(org.apache.iotdb.protocol.influxdb.rpc.thrift.TSStatus status) {
    super(status);
  }

  public SessionTimeoutException(String reason) {
    super(reason);
  }

  public SessionTimeoutException(Throwable cause) {
    super(cause);
  }

  public SessionTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
