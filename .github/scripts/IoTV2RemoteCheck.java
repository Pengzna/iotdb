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

import java.sql.ResultSet;

import org.apache.iotdb.it.env.cluster.env.SimpleEnv;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

class IoTV2RemoteCheck {
  public static void main(String[] args) throws Exception {
    String className = args[0];
    String simpleName = className.substring(className.lastIndexOf('.') + 1);
    SimpleEnv server = new SimpleEnv();
    server.setTestClassName(simpleName);
    var config = server.getConfig();
    if (simpleName.equals("IoTDBDatetimeFormatIT")) {
      config.getCommonConfig().setTimestampPrecisionCheckEnabled(false);
    }
    if (simpleName.equals("IoTDBInsertMultiPartitionIT")) {
      config.getDataNodeCommonConfig().setWriteMemoryProportion("10000000:1");
    }
    if (simpleName.equals("IoTDBRestServiceIT") || simpleName.equals("GrafanaApiServiceIT")) {
      config.getDataNodeConfig().setEnableRestService(true);
    }
    if (simpleName.equals("IoTDBRestServiceIT")) {
      config.getCommonConfig().setEnforceStrongPassword(false);
    }
    int exit;
    try {
      server.initClusterEnvironment();
      try (var connection = server.getConnection();
          var statement = connection.createStatement();
          ResultSet variables = statement.executeQuery("show variables")) {
        boolean iotv2 = false;
        while (variables.next()) {
          if ("DataRegionConsensusProtocolClass".equals(variables.getString(1))
              && "org.apache.iotdb.consensus.iot.IoTConsensusV2".equals(variables.getString(2))) {
            System.out.println("Server " + variables.getString(1) + " = " + variables.getString(2));
            iotv2 = true;
          }
        }
        if (!iotv2) {
          throw new IllegalStateException("Server did not report IoTConsensusV2");
        }
      }
      System.setProperty("RemotePort", server.getPort());
      System.setProperty("RemoteRestPort", Integer.toString(server.getDataNodeWrapper(0).getRestServicePort()));
      Result result = new JUnitCore().run(Class.forName(className));
      for (Failure failure : result.getFailures()) {
        System.out.println(failure.getTestHeader());
        System.out.println(failure.getTrace());
      }
      System.out.printf("Ran %d, failed %d, ignored %d, assumptions %d%n",
          result.getRunCount(), result.getFailureCount(), result.getIgnoreCount(),
          result.getAssumptionFailureCount());
      exit = result.wasSuccessful() && result.getRunCount() > 0 ? 0 : 1;
    } catch (Exception e) {
      e.printStackTrace();
      exit = 2;
    } finally {
      server.cleanClusterEnvironment();
    }
    System.exit(exit);
  }
}
