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

include "common.thrift"
namespace java org.apache.iotdb.consensus.pipe.thrift

struct TCommitId {
  1:required i64 commitIndex
  2:required i32 rebootTimes
}

struct TPipeConsensusTransferReq {
  1:required i8 version
  2:required i16 type
  3:required TCommitId commitId
  4:required binary body
}

struct TPipeConsensusTransferResp {
  1:required common.TSStatus status
  2:optional binary body
}

struct TPipeConsensusBatchTransferReq {
  1:required list<TPipeConsensusTransferReq> batchReqs
}

struct TPipeConsensusBatchTransferResp {
  1:required list<TPipeConsensusTransferResp> batchResps
}

service PipeConsensusIService {
  /**
  * Transfer stream data in a given ConsensusGroup, used by PipeConsensus
  **/
  TPipeConsensusTransferResp pipeConsensusTransfer(TPipeConsensusTransferReq req)

  /**
  * Transfer batch data in a given ConsensusGroup, used by PipeConsensus
  **/
  TPipeConsensusBatchTransferResp pipeConsensusBatchTransfer(TPipeConsensusBatchTransferReq req)
}