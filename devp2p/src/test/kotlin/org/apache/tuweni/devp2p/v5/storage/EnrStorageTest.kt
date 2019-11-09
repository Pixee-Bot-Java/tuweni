package org.apache.tuweni.devp2p.v5.storage

import org.apache.tuweni.crypto.Hash
import org.apache.tuweni.crypto.SECP256K1
import org.apache.tuweni.devp2p.EthereumNodeRecord
import org.apache.tuweni.devp2p.v5.ENRStorage
import org.apache.tuweni.junit.BouncyCastleExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetAddress

@ExtendWith(BouncyCastleExtension::class)
class EnrStorageTest {

  private val storage: ENRStorage = DefaultENRStorage()


  @Test
  fun setPersistsAndFindRetrievesNodeRecord() {
    val enr = EthereumNodeRecord.toRLP(SECP256K1.KeyPair.random(), ip = InetAddress.getLocalHost())

    storage.set(enr)

    val nodeId = Hash.sha2_256(enr)
    storage.find(nodeId)
  }

}