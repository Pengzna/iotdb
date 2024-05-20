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

package org.apache.iotdb.db.queryengine.execution.operator.process;

import org.apache.iotdb.db.queryengine.execution.MemoryEstimationHelper;
import org.apache.iotdb.db.queryengine.execution.operator.Operator;
import org.apache.iotdb.db.queryengine.execution.operator.OperatorContext;
import org.apache.iotdb.db.utils.columngenerator.ColumnGenerator;

import com.google.common.util.concurrent.ListenableFuture;
import org.apache.tsfile.block.column.Column;
import org.apache.tsfile.read.common.block.TsBlock;
import org.apache.tsfile.utils.RamUsageEstimator;

/**
 * This operator can be used to insert a column in TsBlock, the column is generated by
 * StaticColumnGenerator which is decided when analyzing.
 */
public class ColumnInjectOperator implements ProcessOperator {

  private static final long INSTANCE_SIZE =
      RamUsageEstimator.shallowSizeOfInstance(ColumnInjectOperator.class);
  private final OperatorContext operatorContext;
  private final Operator child;
  private final int targetInjectIndex;
  private final ColumnGenerator columnGenerator;
  private final long maxExtraColumnSize;

  public ColumnInjectOperator(
      OperatorContext operatorContext,
      Operator childOperator,
      ColumnGenerator columnGenerator,
      int targetInjectIndex,
      long maxExtraColumnSize) {
    this.columnGenerator = columnGenerator;
    this.operatorContext = operatorContext;
    this.child = childOperator;
    this.targetInjectIndex = targetInjectIndex;
    this.maxExtraColumnSize = maxExtraColumnSize;
  }

  @Override
  public OperatorContext getOperatorContext() {
    return operatorContext;
  }

  @Override
  public TsBlock next() throws Exception {
    if (!child.hasNextWithTimer()) {
      return null;
    }

    TsBlock tsBlock = child.nextWithTimer();
    if (tsBlock == null) {
      return null;
    }

    Column[] columnsToBeInjected = columnGenerator.generate(tsBlock.getPositionCount());

    // If targetIndex is the tail of array, we can use append() to improve performance
    tsBlock =
        targetInjectIndex == tsBlock.getValueColumnCount()
            ? tsBlock.appendValueColumns(columnsToBeInjected)
            : tsBlock.insertValueColumn(targetInjectIndex, columnsToBeInjected);

    return tsBlock;
  }

  @Override
  public boolean hasNext() throws Exception {
    return child.hasNextWithTimer();
  }

  @Override
  public boolean isFinished() throws Exception {
    return !this.hasNextWithTimer();
  }

  @Override
  public void close() throws Exception {
    child.close();
  }

  @Override
  public ListenableFuture<?> isBlocked() {
    return child.isBlocked();
  }

  @Override
  public long calculateMaxPeekMemory() {
    return child.calculateMaxPeekMemoryWithCounter() + maxExtraColumnSize;
  }

  @Override
  public long calculateMaxReturnSize() {
    return maxExtraColumnSize + child.calculateMaxReturnSize();
  }

  @Override
  public long calculateRetainedSizeAfterCallingNext() {
    return child.calculateRetainedSizeAfterCallingNext();
  }

  @Override
  public long ramBytesUsed() {
    return INSTANCE_SIZE
        + MemoryEstimationHelper.getEstimatedSizeOfAccountableObject(child)
        + MemoryEstimationHelper.getEstimatedSizeOfAccountableObject(operatorContext);
  }
}
