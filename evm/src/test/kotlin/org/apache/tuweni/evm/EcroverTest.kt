// Copyright The Tuweni Authors
// SPDX-License-Identifier: Apache-2.0
package org.apache.tuweni.evm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import kotlinx.coroutines.runBlocking
import org.apache.lucene.index.IndexWriter
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.bytes.Bytes32
import org.apache.tuweni.eth.AccountState
import org.apache.tuweni.eth.EthJsonModule
import org.apache.tuweni.eth.Hash
import org.apache.tuweni.eth.precompiles.Registry
import org.apache.tuweni.eth.repository.BlockchainRepository
import org.apache.tuweni.eth.repository.TransientStateRepository
import org.apache.tuweni.evm.impl.EvmVmImpl
import org.apache.tuweni.genesis.Genesis
import org.apache.tuweni.io.Resources
import org.apache.tuweni.junit.BouncyCastleExtension
import org.apache.tuweni.junit.LuceneIndexWriter
import org.apache.tuweni.junit.LuceneIndexWriterExtension
import org.apache.tuweni.rlp.RLP
import org.apache.tuweni.trie.MerkleStorage
import org.apache.tuweni.trie.MerkleTrie
import org.apache.tuweni.trie.StoredMerklePatriciaTrie
import org.apache.tuweni.units.bigints.UInt256
import org.apache.tuweni.units.ethereum.Wei
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.util.stream.Stream

@ExtendWith(LuceneIndexWriterExtension::class, BouncyCastleExtension::class)
class EcroverTest {
  companion object {

    val mapper = ObjectMapper(YAMLFactory())

    init {
      mapper.registerModule(EthJsonModule())
    }

    @JvmStatic
    @Throws(IOException::class)
    private fun findFrontierTests(): Stream<Arguments> {
      return Stream.of(
        EcroverTest::class.java.getResource("/ecrecover.yaml").openConnection().getInputStream().use { input ->
          prepareTest(
            input,
          )
        },
      )
    }

    @Throws(IOException::class)
    private fun findTests(glob: String): Stream<Arguments> {
      return Resources.find(glob).map { url ->
        try {
          url.openConnection().getInputStream().use { input -> prepareTest(input) }
        } catch (e: IOException) {
          throw UncheckedIOException("Could not read $url", e)
        }
      }.sorted { o1, o2 -> (o1.get()[0] as String).compareTo(o2.get()[0] as String) }.filter() {
        val test = it.get()[1] as OpcodeTestModel
        test.exceptionalHaltReason == "NONE"
      }
    }

    @Throws(IOException::class)
    private fun prepareTest(input: InputStream): Arguments {
      val test = mapper.readValue(input, OpcodeTestModel::class.java)
      return Arguments.of(test.name + "-" + test.index, test)
    }
  }

  private var writer: IndexWriter? = null

  @BeforeEach
  fun setUp(@LuceneIndexWriter newWriter: IndexWriter) {
    writer = newWriter
  }

  @ParameterizedTest(name = "Frontier {index}: {0}")
  @MethodSource("findFrontierTests")
  fun runFrontierReferenceTests(testName: String, test: OpcodeTestModel) {
    runReferenceTests(testName, HardFork.FRONTIER, test)
  }

  private fun runReferenceTests(testName: String, hardFork: HardFork, test: OpcodeTestModel) = runBlocking {
    Assertions.assertNotNull(testName)
    println(testName)
    val repository = BlockchainRepository.inMemory(Genesis.dev())
    test.before.accounts.forEach { info ->
      runBlocking {
        val accountState = AccountState(
          info.nonce,
          info.balance,
          Hash.fromBytes(MerkleTrie.EMPTY_TRIE_ROOT_HASH),
          Hash.hash(info.code),
        )
        repository.storeAccount(info.address, accountState)
        repository.storeCode(info.code)
        val accountStorage = info.storage

        for (entry in accountStorage) {
          repository.storeAccountValue(info.address, Hash.hash(entry.key), Bytes32.leftPad(entry.value))
        }
      }
    }
    val changesRepository = TransientStateRepository(repository)
    val vm = EthereumVirtualMachine(changesRepository, repository, Registry.istanbul, EvmVmImpl::create)
    vm.start()
    try {
      val result = vm.execute(
        test.sender,
        test.receiver,
        test.value,
        test.code,
        test.inputData,
        test.gas,
        test.gasPrice,
        test.coinbase,
        UInt256.valueOf(test.number),
        UInt256.valueOf(test.timestamp),
        test.gasLimit,
        UInt256.fromBytes(Bytes32.leftPad(test.difficultyBytes)),
        test.chainId,
        CallKind.CALL,
        hardFork,
      )

      assertEquals(EVMExecutionStatusCode.SUCCESS, result.statusCode)

      for (i in 0 until test.after.stack.size) {
        assertEquals(Bytes32.leftPad(test.after.stack[i]), result.state.stack.get(i), "Mismatch of stack elements")
      }

      test.after.accounts.forEach { info ->
        runBlocking acct@{
          val address = info.address
          if (Registry.istanbul.contains(address)) {
            return@acct
          }
          assertTrue(changesRepository.accountsExists(address), address.toHexString())
          val accountState = changesRepository.getAccount(address)
          val balance = accountState?.balance ?: Wei.valueOf(0)
          assertEquals(
            info.balance,
            balance,
            "balance doesn't match: " + address.toHexString() + ":" + if (balance > info.balance) {
              balance.subtract(
                info.balance,
              ).toString()
            } else {
              info.balance.subtract(balance).toString()
            },
          )
          assertEquals(info.nonce, accountState!!.nonce)

          for (stored in info.storage) {
            val changed = changesRepository.getAccountStoreValue(address, Hash.hash(stored.key))?.let {
              RLP.decodeValue(
                it,
              )
            } ?: UInt256.ZERO
            assertEquals(stored.value, Bytes32.leftPad(changed)) {
              runBlocking {
                val account = changesRepository.getAccount(address) ?: changesRepository.newAccountState()
                val tree = StoredMerklePatriciaTrie.storingBytes(
                  object : MerkleStorage {
                    override suspend fun get(hash: Bytes32): Bytes? {
                      return changesRepository.transientState.get(hash)
                    }

                    override suspend fun put(hash: Bytes32, content: Bytes) {
                      return changesRepository.transientState.put(hash, content)
                    }
                  },
                  account.storageRoot,
                )
                "mismatched account storage for address $address at slot ${stored.key}\n" + tree.printAsString()
              }
            }
          }
        }
      }

      test.after.logs.let {
        val ourLogs = (result.hostContext as TransactionalEVMHostContext).getLogs()
        for (i in 0 until it.size) {
          assertEquals(it[i].logger, ourLogs[i].logger)
          assertEquals(it[i].data, ourLogs[i].data)
          assertEquals(it[i].topics.size, ourLogs[i].topics.size)
          for (j in 0 until it[i].topics.size) {
            assertEquals(it[i].topics[j], ourLogs[i].topics[j])
          }
        }
      }
    } finally {
      vm.stop()
    }
  }
}
