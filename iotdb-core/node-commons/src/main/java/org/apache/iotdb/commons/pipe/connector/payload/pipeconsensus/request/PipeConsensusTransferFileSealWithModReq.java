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

package org.apache.iotdb.commons.pipe.connector.payload.pipeconsensus.request;

import org.apache.iotdb.consensus.pipe.thrift.TCommitId;
import org.apache.iotdb.consensus.pipe.thrift.TPipeConsensusTransferReq;

import org.apache.tsfile.utils.PublicBAOS;
import org.apache.tsfile.utils.ReadWriteIOUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class PipeConsensusTransferFileSealWithModReq extends TPipeConsensusTransferReq {

  private transient List<String> fileNames;
  private transient List<Long> fileLengths;
  private transient Map<String, String> parameters;

  protected abstract PipeConsensusRequestType getPlanType();

  /////////////////////////////// Thrift ///////////////////////////////

  protected PipeConsensusTransferFileSealWithModReq convertToTPipeConsensusTransferReq(
      List<String> fileNames,
      List<Long> fileLengths,
      Map<String, String> parameters,
      TCommitId commitId)
      throws IOException {

    this.fileNames = fileNames;
    this.fileLengths = fileLengths;
    this.parameters = parameters;

    this.commitId = commitId;
    this.version = PipeConsensusRequestVersion.VERSION_1.getVersion();
    this.type = getPlanType().getType();
    try (final PublicBAOS byteArrayOutputStream = new PublicBAOS();
        final DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream)) {
      ReadWriteIOUtils.write(fileNames.size(), outputStream);
      for (String fileName : fileNames) {
        ReadWriteIOUtils.write(fileName, outputStream);
      }
      ReadWriteIOUtils.write(fileLengths.size(), outputStream);
      for (Long fileLength : fileLengths) {
        ReadWriteIOUtils.write(fileLength, outputStream);
      }
      ReadWriteIOUtils.write(parameters.size(), outputStream);
      for (final Map.Entry<String, String> entry : parameters.entrySet()) {
        ReadWriteIOUtils.write(entry.getKey(), outputStream);
        ReadWriteIOUtils.write(entry.getValue(), outputStream);
      }
      this.body = ByteBuffer.wrap(byteArrayOutputStream.getBuf(), 0, byteArrayOutputStream.size());
    }

    return this;
  }

  public PipeConsensusTransferFileSealWithModReq translateFromTPipeConsensusTransferReq(
      TPipeConsensusTransferReq req) {
    fileNames = new ArrayList<>();
    int size = ReadWriteIOUtils.readInt(req.body);
    for (int i = 0; i < size; ++i) {
      fileNames.add(ReadWriteIOUtils.readString(req.body));
    }

    fileLengths = new ArrayList<>();
    size = ReadWriteIOUtils.readInt(req.body);
    for (int i = 0; i < size; ++i) {
      fileLengths.add(ReadWriteIOUtils.readLong(req.body));
    }

    parameters = new HashMap<>();
    size = ReadWriteIOUtils.readInt(req.body);
    for (int i = 0; i < size; ++i) {
      final String key = ReadWriteIOUtils.readString(req.body);
      final String value = ReadWriteIOUtils.readString(req.body);
      parameters.put(key, value);
    }

    version = req.version;
    type = req.type;
    body = req.body;
    commitId = req.commitId;

    return this;
  }

  /////////////////////////////// Object ///////////////////////////////

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    PipeConsensusTransferFileSealWithModReq that = (PipeConsensusTransferFileSealWithModReq) obj;
    return Objects.equals(fileNames, that.fileNames)
        && Objects.equals(fileLengths, that.fileLengths)
        && Objects.equals(parameters, that.parameters)
        && Objects.equals(version, that.version)
        && Objects.equals(type, that.type)
        && Objects.equals(body, that.body)
        && Objects.equals(commitId, that.commitId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fileNames, fileLengths, parameters, version, type, body, commitId);
  }
}
