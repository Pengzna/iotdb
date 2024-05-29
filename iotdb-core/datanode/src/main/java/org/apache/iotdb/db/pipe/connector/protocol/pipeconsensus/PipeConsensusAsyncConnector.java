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

package org.apache.iotdb.db.pipe.connector.protocol.pipeconsensus;

import org.apache.iotdb.common.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.common.rpc.thrift.TConsensusGroupType;
import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.commons.client.IClientManager;
import org.apache.iotdb.commons.conf.CommonConfig;
import org.apache.iotdb.commons.conf.CommonDescriptor;
import org.apache.iotdb.commons.consensus.index.ProgressIndex;
import org.apache.iotdb.commons.pipe.config.constant.PipeConnectorConstant;
import org.apache.iotdb.commons.pipe.connector.protocol.IoTDBConnector;
import org.apache.iotdb.commons.pipe.event.EnrichedEvent;
import org.apache.iotdb.consensus.pipe.PipeConsensus;
import org.apache.iotdb.consensus.pipe.client.AsyncPipeConsensusServiceClient;
import org.apache.iotdb.consensus.pipe.thrift.TCommitId;
import org.apache.iotdb.consensus.pipe.thrift.TPipeConsensusTransferReq;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.consensus.DataRegionConsensusImpl;
import org.apache.iotdb.db.pipe.connector.protocol.pipeconsensus.handler.PipeConsensusTabletBatchEventHandler;
import org.apache.iotdb.db.pipe.connector.protocol.pipeconsensus.handler.PipeConsensusTabletInsertNodeEventHandler;
import org.apache.iotdb.db.pipe.connector.protocol.pipeconsensus.handler.PipeConsensusTsFileInsertionEventHandler;
import org.apache.iotdb.db.pipe.connector.protocol.pipeconsensus.payload.builder.PipeConsensusAsyncBatchReqBuilder;
import org.apache.iotdb.db.pipe.connector.protocol.pipeconsensus.payload.request.PipeConsensusTabletBinaryReq;
import org.apache.iotdb.db.pipe.connector.protocol.pipeconsensus.payload.request.PipeConsensusTabletInsertNodeReq;
import org.apache.iotdb.db.pipe.event.common.heartbeat.PipeHeartbeatEvent;
import org.apache.iotdb.db.pipe.event.common.tablet.PipeInsertNodeTabletInsertionEvent;
import org.apache.iotdb.db.pipe.event.common.tablet.PipeRawTabletInsertionEvent;
import org.apache.iotdb.db.pipe.event.common.tsfile.PipeTsFileInsertionEvent;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.write.InsertNode;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeConnectorRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;
import org.apache.iotdb.pipe.api.event.dml.insertion.TsFileInsertionEvent;
import org.apache.iotdb.pipe.api.exception.PipeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// TODO: Optimize the network and disk io for TsFile onComplete
// TODO: support Tablet Batch
public class PipeConsensusAsyncConnector extends IoTDBConnector {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipeConsensusAsyncConnector.class);

  private static final String CUSTOMIZE_EXCEPTION_MSG =
      "Failed to customize pipeConsensusAsyncConnector because there isn't consensusGroupId passed in. Please check your construct parameters.";

  private static final String ENQUEUE_EXCEPTION_MSG =
      "Timeout: PipeConsensusConnector offers an event into transferBuffer failed, because transferBuffer is full.";

  private static final String THRIFT_ERROR_FORMATTER_WITHOUT_ENDPOINT =
      "Failed to borrow client from client pool or exception occurred "
          + "when sending to receiver.";

  private static final String THRIFT_ERROR_FORMATTER_WITH_ENDPOINT =
      "Failed to borrow client from client pool or exception occurred "
          + "when sending to receiver %s:%s.";

  private static final CommonConfig COMMON_CONFIG = CommonDescriptor.getInstance().getConfig();

  private final BlockingQueue<Event> retryEventQueue = new LinkedBlockingQueue<>();

  private final BlockingQueue<Event> transferBuffer =
      new LinkedBlockingDeque<>(COMMON_CONFIG.getPipeConsensusEventBufferSize());

  private final AtomicBoolean isClosed = new AtomicBoolean(false);

  private final int thisDataNodeId = IoTDBDescriptor.getInstance().getConfig().getDataNodeId();

  private int consensusGroupId;

  private PipeConsensusSyncConnector retryConnector;

  private IClientManager<TEndPoint, AsyncPipeConsensusServiceClient> asyncTransferClientManager;

  private PipeConsensusAsyncBatchReqBuilder tabletBatchBuilder;

  @Override
  public void customize(PipeParameters parameters, PipeConnectorRuntimeConfiguration configuration)
      throws Exception {
    super.customize(parameters, configuration);

    // Get consensusGroupId from parameters passed by PipeConsensusImpl
    if (!parameters.hasAttribute(PipeConnectorConstant.CONNECTOR_CONSENSUS_GROUP_ID_KEY)) {
      throw new PipeException(CUSTOMIZE_EXCEPTION_MSG);
    }
    consensusGroupId = parameters.getInt(PipeConnectorConstant.CONNECTOR_CONSENSUS_GROUP_ID_KEY);

    // In PipeConsensus, one pipeConsensusTask corresponds to a pipeConsensusConnector. Thus,
    // `nodeUrls` here actually is a singletonList that contains one peer's TEndPoint. But here we
    // retain the implementation of list to cope with possible future expansion
    retryConnector = new PipeConsensusSyncConnector(nodeUrls, consensusGroupId, thisDataNodeId);
    retryConnector.customize(parameters, configuration);
    asyncTransferClientManager =
        ((PipeConsensus) DataRegionConsensusImpl.getInstance()).getAsyncClientManager();

    if (isTabletBatchModeEnabled) {
      tabletBatchBuilder =
          new PipeConsensusAsyncBatchReqBuilder(
              parameters,
              new TConsensusGroupId(TConsensusGroupType.DataRegion, consensusGroupId),
              thisDataNodeId);
    }

    // currently, tablet batch is false by default in PipeConsensus;
    isTabletBatchModeEnabled = false;
  }

  /** Add an event to transferBuffer, whose events will be asynchronizedly transfer to receiver. */
  private boolean addEvent2Buffer(Event event) {
    try {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(
            "PipeConsensus-ConsensusGroup-{}: no.{} event-{} added to connector buffer",
            consensusGroupId,
            ((EnrichedEvent) event).getCommitId(),
            event);
      }
      boolean result =
          transferBuffer.offer(
              event,
              COMMON_CONFIG.getPipeConsensusEventEnqueueTimeoutInMs(),
              TimeUnit.MILLISECONDS);
      // add reference
      if (result) {
        ((EnrichedEvent) event).increaseReferenceCount(PipeConsensusAsyncConnector.class.getName());
      }
      return result;
    } catch (InterruptedException e) {
      LOGGER.info("PipeConsensusConnector transferBuffer queue offer is interrupted.", e);
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * if one event is successfully processed by receiver in PipeConsensus, we will remove this event
   * from transferBuffer in order to transfer other event.
   */
  public synchronized void removeEventFromBuffer(Event event) {
    LOGGER.info(
        "PipeConsensus-ConsensusGroup-{}: one event-{} successfully received by the follower, will be removed from queue, queue size = {}, limit size = {}",
        consensusGroupId,
        event,
        transferBuffer.size(),
        COMMON_CONFIG.getPipeConsensusEventBufferSize());

    Iterator<Event> iterator = transferBuffer.iterator();
    Event current = iterator.next();
    while (!current.equals(event) && iterator.hasNext()) {
      current = iterator.next();
    }
    iterator.remove();
    // decrease reference count
    ((EnrichedEvent) event)
        .decreaseReferenceCount(PipeConsensusAsyncConnector.class.getName(), true);
  }

  @Override
  public void handshake() throws Exception {
    // do nothing
    // PipeConsensus doesn't need to do handshake, since nodes in same consensusGroup/cluster
    // usually have same configuration.
  }

  @Override
  public void heartbeat() throws Exception {
    // do nothing
  }

  @Override
  public void transfer(TabletInsertionEvent tabletInsertionEvent) throws Exception {
    boolean enqueueResult = addEvent2Buffer(tabletInsertionEvent);
    if (!enqueueResult) {
      throw new PipeException(ENQUEUE_EXCEPTION_MSG);
    }

    syncTransferQueuedEventsIfNecessary();

    // batch transfer tablets.
    if (isTabletBatchModeEnabled) {
      if (tabletBatchBuilder.onEvent(tabletInsertionEvent)) {
        final PipeConsensusTabletBatchEventHandler pipeConsensusTabletBatchEventHandler =
            new PipeConsensusTabletBatchEventHandler(tabletBatchBuilder, this);

        transfer(pipeConsensusTabletBatchEventHandler);

        tabletBatchBuilder.onSuccess();
      }
    } else {
      TCommitId tCommitId;
      TConsensusGroupId tConsensusGroupId =
          new TConsensusGroupId(TConsensusGroupType.DataRegion, consensusGroupId);
      // tabletInsertionEvent instanceof PipeInsertNodeTabletInsertionEvent)
      final PipeInsertNodeTabletInsertionEvent pipeInsertNodeTabletInsertionEvent =
          (PipeInsertNodeTabletInsertionEvent) tabletInsertionEvent;
      tCommitId =
          new TCommitId(
              pipeInsertNodeTabletInsertionEvent.getCommitId(),
              pipeInsertNodeTabletInsertionEvent.getRebootTimes());

      // We increase the reference count for this event to determine if the event may be released.
      if (!pipeInsertNodeTabletInsertionEvent.increaseReferenceCount(
          PipeConsensusAsyncConnector.class.getName())) {
        pipeInsertNodeTabletInsertionEvent.decreaseReferenceCount(
            PipeConsensusAsyncConnector.class.getName(), false);
        return;
      }

      final InsertNode insertNode =
          pipeInsertNodeTabletInsertionEvent.getInsertNodeViaCacheIfPossible();
      final ProgressIndex progressIndex = pipeInsertNodeTabletInsertionEvent.getProgressIndex();
      final TPipeConsensusTransferReq pipeConsensusTransferReq =
          Objects.isNull(insertNode)
              ? PipeConsensusTabletBinaryReq.toTPipeConsensusTransferReq(
                  pipeInsertNodeTabletInsertionEvent.getByteBuffer(),
                  tCommitId,
                  tConsensusGroupId,
                  progressIndex,
                  thisDataNodeId)
              : PipeConsensusTabletInsertNodeReq.toTPipeConsensusTransferReq(
                  insertNode, tCommitId, tConsensusGroupId, progressIndex, thisDataNodeId);
      final PipeConsensusTabletInsertNodeEventHandler pipeConsensusInsertNodeReqHandler =
          new PipeConsensusTabletInsertNodeEventHandler(
              pipeInsertNodeTabletInsertionEvent, pipeConsensusTransferReq, this);

      transfer(pipeConsensusInsertNodeReqHandler);
    }
  }

  private void transfer(
      final PipeConsensusTabletBatchEventHandler pipeConsensusTabletBatchEventHandler) {
    AsyncPipeConsensusServiceClient client = null;
    try {
      client = asyncTransferClientManager.borrowClient(getFollowerUrl());
      pipeConsensusTabletBatchEventHandler.transfer(client);
    } catch (final Exception ex) {
      logOnClientException(client, ex);
      pipeConsensusTabletBatchEventHandler.onError(ex);
    }
  }

  private void transfer(
      final PipeConsensusTabletInsertNodeEventHandler pipeConsensusInsertNodeReqHandler) {
    AsyncPipeConsensusServiceClient client = null;
    try {
      client = asyncTransferClientManager.borrowClient(getFollowerUrl());
      pipeConsensusInsertNodeReqHandler.transfer(client);
    } catch (final Exception ex) {
      logOnClientException(client, ex);
      pipeConsensusInsertNodeReqHandler.onError(ex);
    }
  }

  @Override
  public void transfer(TsFileInsertionEvent tsFileInsertionEvent) throws Exception {
    boolean enqueueResult = addEvent2Buffer(tsFileInsertionEvent);
    if (!enqueueResult) {
      throw new PipeException(ENQUEUE_EXCEPTION_MSG);
    }

    syncTransferQueuedEventsIfNecessary();
    transferBatchedEventsIfNecessary();

    if (!(tsFileInsertionEvent instanceof PipeTsFileInsertionEvent)) {
      LOGGER.warn(
          "PipeConsensusAsyncConnector only support PipeTsFileInsertionEvent. Current event: {}.",
          tsFileInsertionEvent);
      return;
    }

    final PipeTsFileInsertionEvent pipeTsFileInsertionEvent =
        (PipeTsFileInsertionEvent) tsFileInsertionEvent;
    TCommitId tCommitId =
        new TCommitId(
            pipeTsFileInsertionEvent.getCommitId(), pipeTsFileInsertionEvent.getRebootTimes());
    TConsensusGroupId tConsensusGroupId =
        new TConsensusGroupId(TConsensusGroupType.DataRegion, consensusGroupId);
    // We increase the reference count for this event to determine if the event may be released.
    if (!pipeTsFileInsertionEvent.increaseReferenceCount(
        PipeConsensusAsyncConnector.class.getName())) {
      pipeTsFileInsertionEvent.decreaseReferenceCount(
          PipeConsensusAsyncConnector.class.getName(), false);
      return;
    }

    try {
      // Just in case. To avoid the case that exception occurred when constructing the handler.
      if (!pipeTsFileInsertionEvent.getTsFile().exists()) {
        throw new FileNotFoundException(pipeTsFileInsertionEvent.getTsFile().getAbsolutePath());
      }

      final PipeConsensusTsFileInsertionEventHandler pipeConsensusTsFileInsertionEventHandler =
          new PipeConsensusTsFileInsertionEventHandler(
              pipeTsFileInsertionEvent, this, tCommitId, tConsensusGroupId, thisDataNodeId);

      transfer(pipeConsensusTsFileInsertionEventHandler);
    } catch (Exception e) {
      // Just in case. To avoid the case that exception occurred when constructing the handler.
      pipeTsFileInsertionEvent.decreaseReferenceCount(
          PipeConsensusAsyncConnector.class.getName(), false);
      throw e;
    }
  }

  private void transfer(
      final PipeConsensusTsFileInsertionEventHandler pipeConsensusTsFileInsertionEventHandler) {
    AsyncPipeConsensusServiceClient client = null;
    try {
      client = asyncTransferClientManager.borrowClient(getFollowerUrl());
      pipeConsensusTsFileInsertionEventHandler.transfer(client);
    } catch (final Exception ex) {
      logOnClientException(client, ex);
      pipeConsensusTsFileInsertionEventHandler.onError(ex);
    }
  }

  /**
   * PipeConsensus only need transfer heartbeat event here. And heartbeat event doesn't need to be
   * added to transferBuffer.
   */
  @Override
  public void transfer(Event event) throws Exception {
    syncTransferQueuedEventsIfNecessary();
    transferBatchedEventsIfNecessary();

    if (!(event instanceof PipeHeartbeatEvent)) {
      LOGGER.warn(
          "PipeConsensusAsyncConnector does not support transferring generic event: {}.", event);
      return;
    }

    retryConnector.transfer(event);
  }

  /** Try its best to commit data in order. Flush can also be a trigger to transfer batched data. */
  private void transferBatchedEventsIfNecessary() throws IOException {
    if (!isTabletBatchModeEnabled || tabletBatchBuilder.isEmpty()) {
      return;
    }

    transfer(new PipeConsensusTabletBatchEventHandler(tabletBatchBuilder, this));

    tabletBatchBuilder.onSuccess();
  }

  /**
   * Transfer queued {@link Event}s which are waiting for retry.
   *
   * @throws Exception if an error occurs. The error will be handled by pipe framework, which will
   *     retry the {@link Event} and mark the {@link Event} as failure and stop the pipe if the
   *     retry times exceeds the threshold.
   */
  private synchronized void syncTransferQueuedEventsIfNecessary() throws Exception {
    while (!retryEventQueue.isEmpty()) {
      synchronized (this) {
        if (isClosed.get() || retryEventQueue.isEmpty()) {
          return;
        }

        final Event peekedEvent = retryEventQueue.peek();
        // do transfer
        if (peekedEvent instanceof PipeInsertNodeTabletInsertionEvent) {
          retryConnector.transfer((PipeInsertNodeTabletInsertionEvent) peekedEvent);
        } else if (peekedEvent instanceof PipeRawTabletInsertionEvent) {
          retryConnector.transfer((PipeRawTabletInsertionEvent) peekedEvent);
        } else if (peekedEvent instanceof PipeTsFileInsertionEvent) {
          retryConnector.transfer((PipeTsFileInsertionEvent) peekedEvent);
        } else {
          if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                "PipeConsensusAsyncConnector does not support transfer generic event: {}.",
                peekedEvent);
          }
        }
        // release resource
        if (peekedEvent instanceof EnrichedEvent) {
          ((EnrichedEvent) peekedEvent)
              .decreaseReferenceCount(PipeConsensusAsyncConnector.class.getName(), true);
        }

        final Event polledEvent = retryEventQueue.poll();
        if (polledEvent != peekedEvent) {
          if (LOGGER.isErrorEnabled()) {
            LOGGER.error(
                "The event polled from the queue is not the same as the event peeked from the queue. "
                    + "Peeked event: {}, polled event: {}.",
                peekedEvent,
                polledEvent);
          }
        }
        if (polledEvent != null && LOGGER.isDebugEnabled()) {
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Polled event {} from retry queue.", polledEvent);
          }
          // poll it from transferBuffer
          removeEventFromBuffer(polledEvent);
        }
      }
    }
  }

  /**
   * Add failure event to retry queue.
   *
   * @param event event to retry
   */
  public void addFailureEventToRetryQueue(final Event event) {
    if (isClosed.get()) {
      if (event instanceof EnrichedEvent) {
        ((EnrichedEvent) event).clearReferenceCount(PipeConsensusAsyncConnector.class.getName());
      }
      return;
    }

    retryEventQueue.offer(event);
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "PipeConsensus-ConsensusGroup-{}: Event {} transfer failed, will be added to retry queue.",
          consensusGroupId,
          event);
    }

    if (isClosed.get()) {
      if (event instanceof EnrichedEvent) {
        ((EnrichedEvent) event).clearReferenceCount(PipeConsensusAsyncConnector.class.getName());
      }
    }
  }

  /**
   * Add failure events to retry queue.
   *
   * @param events events to retry
   */
  public void addFailureEventsToRetryQueue(final Iterable<Event> events) {
    for (final Event event : events) {
      addFailureEventToRetryQueue(event);
    }
  }

  public synchronized void clearRetryEventsReferenceCount() {
    while (!retryEventQueue.isEmpty()) {
      final Event event = retryEventQueue.poll();
      if (event instanceof EnrichedEvent) {
        ((EnrichedEvent) event).clearReferenceCount(PipeConsensusAsyncConnector.class.getName());
      }
    }
  }

  private void logOnClientException(
      final AsyncPipeConsensusServiceClient client, final Exception e) {
    if (client == null) {
      LOGGER.warn(THRIFT_ERROR_FORMATTER_WITHOUT_ENDPOINT, e);
    } else {
      LOGGER.warn(
          String.format(
              THRIFT_ERROR_FORMATTER_WITH_ENDPOINT,
              client.getTEndpoint().getIp(),
              client.getTEndpoint().getPort()),
          e);
    }
  }

  private TEndPoint getFollowerUrl() {
    // In current pipeConsensus design, one connector corresponds to one follower, so the peers is
    // actually a singleton list
    return nodeUrls.get(0);
  }

  // synchronized to avoid close connector when transfer event
  @Override
  public synchronized void close() throws Exception {
    isClosed.set(true);

    retryConnector.close();
    clearRetryEventsReferenceCount();

    if (tabletBatchBuilder != null) {
      tabletBatchBuilder.close();
    }
  }

  //////////////////////////// TODO: APIs provided for metric framework ////////////////////////////

  public int getTransferBufferSize() {
    return transferBuffer.size();
  }

  public int getRetryBufferSize() {
    return retryEventQueue.size();
  }
}
