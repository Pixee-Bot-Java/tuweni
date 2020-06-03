/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tuweni.devp2p.eth

import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.eth.Block
import org.apache.tuweni.eth.BlockBody
import org.apache.tuweni.eth.BlockHeader
import org.apache.tuweni.eth.Hash
import org.apache.tuweni.eth.TransactionReceipt
import org.apache.tuweni.eth.repository.BlockchainRepository

class EthController(val repository: BlockchainRepository, val requestsManager: EthRequestsManager) {

  suspend fun findTransactionReceipts(hashes: List<Hash>): List<List<TransactionReceipt>> {
    val receipts = ArrayList<List<TransactionReceipt>>()
    hashes.forEach {
      receipts.add(repository.retrieveTransactionReceipts(it))
    }
    return receipts
  }

  suspend fun addNewBlock(newBlock: Block) {
    repository.storeBlock(newBlock)
  }

  suspend fun findBlockBodies(hashes: List<Hash>): List<BlockBody> {
    val bodies = ArrayList<BlockBody>()
    hashes.forEach { hash ->
      repository.retrieveBlockBody(hash)?.let {
        bodies.add(it)
      }
    }
    return bodies
  }

  suspend fun findHeaders(block: Bytes, maxHeaders: Long, skip: Long, reverse: Boolean): List<BlockHeader> {
    val matches = repository.findBlockByHashOrNumber(block)
    val headers = ArrayList<BlockHeader>()
    if (matches.isNotEmpty()) {
      val header = repository.retrieveBlockHeader(matches[0])
      header?.let {
        headers.add(it)
        var blockNumber = it.number
        for (i in 2..maxHeaders) {
          blockNumber = if (reverse) {
            blockNumber.subtract(skip + 1)
          } else {
            blockNumber.add(skip + 1)
          }
          val nextMatches = repository.findBlockByHashOrNumber(blockNumber.toBytes())
          if (nextMatches.isEmpty()) {
            break
          }
          val nextHeader = repository.retrieveBlockHeader(nextMatches[0]) ?: break
          headers.add(nextHeader)
        }
      }
    }
    return headers
  }

  suspend fun addNewBlockHashes(hashes: List<Pair<Hash, Long>>) {
    hashes.forEach { pair ->
      repository.retrieveBlockHeader(pair.first).takeIf { null == it }.apply {
        requestBlockHeader(pair.first)
      }
      repository.retrieveBlockBody(pair.first).takeIf { null == it }.apply {
        requestBlockBody(pair.first)
      }
    }
  }

  fun requestBlockHeader(blockHash: Hash) {
    requestsManager.requestBlockHeader(blockHash)
  }

  private fun requestBlockBody(blockHash: Hash) {
    requestsManager.requestBlockBody(blockHash)
  }

  suspend fun addNewBlockHeaders(connectionId: String, headers: List<BlockHeader>) {
    headers.forEach { header ->
      if (!requestsManager.wasRequested(connectionId, header)) {
        return
      }

      repository.storeBlockHeader(header)
    }
  }
}