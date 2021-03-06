package aes

import AESTestUtils._
import chisel3._
import chiseltest._
import chiseltest.internal.{VerilatorBackendAnnotation, WriteVcdAnnotation}
import chiseltest.experimental.TestOptionBuilder._
import org.scalatest.flatspec.AnyFlatSpec
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.tile.{RoCCCommand, RoCCResponse}
import verif.TLMemoryModel.{State, WordAddr, read}
import verif._


class AccelTopTest extends AnyFlatSpec with ChiselScalatestTester {
  // Hardcoded, since only beatBytes == 16 works for now. Seems like it's a hardcoded value for the DCache
  val beatBytes = 16
  implicit val p: Parameters = VerifTestUtils.getVerifParameters(xLen = 32, beatBytes = beatBytes) // Testing for our 32b RISC-V chip
  val r = new scala.util.Random

  // Temporary storage for keys in key-reusal case
  var prev_key: BigInt      = 0
  var prev_key_addr: BigInt = 0

  def testAccelerator(dut: AESAccelStandaloneBlock, clock: Clock, keySize: Int, encdec: Int, interrupt: Int, rounds: Int, reuse_key_en: Boolean): Boolean = {
    assert(keySize == 128 || keySize == 256 || keySize == -1, s"KeySize must be 128, 256, or -1 (random). Given: $keySize")
    assert(encdec == 0 || encdec == 1 || encdec == -1, s"ENCDEC must be 1 (encrypt), 0 (decrypt), or -1 (random). Given: $encdec")
    assert(interrupt == 0 || interrupt == 1 || interrupt == -1, s"INTERRUPT must be 1 (enable), 0 (disable), or -1 (random). Given: $interrupt")

    // RoCCCommand driver + RoCCResponse receiver
    val driver = new DecoupledDriverMaster[RoCCCommand](clock, dut.module.io.cmd)
    val txProto = new DecoupledTX(new RoCCCommand())
    val monitor = new DecoupledMonitor[RoCCResponse](clock, dut.module.io.resp)
    val receiver = new DecoupledDriverSlave[RoCCResponse](clock, dut.module.io.resp, 0)

    // Mock Memory
    val slaveFn = new TLMemoryModel(dut.to_mem.params)
    val slaveModel = new TLDriverSlave(clock, dut.to_mem, slaveFn, TLMemoryModel.State.empty())
    val slaveMonitor = new TLMonitor(clock, dut.to_mem)

    var cycleCount = 0
    var blocksProcessed = 0
    var allPass = true
    var actualKeySize = 128
    var encrypt = true
    var interrupt_en = 0
    var reuse_key = false

    for (i <- 0 until rounds) {
      // Output: (1: keyAddr, 2: keyData (256b post-padded), 3: srcAddr, 4: textData, 5: destAddr, 6: memState)
      val destructive = r.nextBoolean()
      if (keySize == -1) actualKeySize = if (r.nextBoolean()) 128 else 256
      else actualKeySize = keySize
      if (encdec == -1) encrypt = r.nextBoolean()
      else encrypt = encdec == 1
      if (interrupt == -1) interrupt_en = r.nextInt(2)

      val stim = genAESStim(actualKeySize, r.nextInt(10) + 1, destructive = destructive, if (reuse_key) prev_key_addr else BigInt(-1), beatBytes, r)
      slaveModel.state = stim._6

      // Randomize reuse_key (cannot reuse on first enc/dec, key expansion required)
      if (i != 0 && reuse_key_en) reuse_key = r.nextBoolean()
      if (!reuse_key) {
        prev_key_addr = stim._1
        prev_key = stim._2
      }

//      // Debug Printing
//      println(s"Debug: key size: $actualKeySize")
//      println(s"Debug: encdec: $encrypt")
//      println(s"Debug: $stim")
//      println(s"Debug: Reuse key $reuse_key")
//      println(s"Debug: Destructive $destructive")

      var inputCmd = Seq[DecoupledTX[RoCCCommand]]()
      // Key load instruction
      if (!reuse_key) {
        if (actualKeySize == 128) inputCmd = inputCmd :+ txProto.tx(keyLoad128(stim._1))
        else inputCmd = inputCmd :+ txProto.tx(keyLoad256(stim._1))
      }
      // Address load instruction
      inputCmd = inputCmd :+ txProto.tx(addrLoad(stim._3, stim._5))
      // Encrypt/Decrypt instruction
      if (encrypt) inputCmd = inputCmd :+ txProto.tx(encBlock(stim._4.length, interrupt_en))
      else inputCmd = inputCmd :+ txProto.tx(decBlock(stim._4.length, interrupt_en))
      // Poll instruction
      inputCmd = inputCmd :+ txProto.tx(getStatus(1)) // RD hardcoded as not affected by accel.
      driver.push(inputCmd)

      // Each block takes at least 50 cycles, will auto-increment
      clock.step(50 * stim._4.length)
      cycleCount += 50 * stim._4.length

      // Checking busy status (should still be busy)
      assert(dut.module.io.busy.peek().litToBoolean, "Accelerator de-asserted busy before interrupt was raised.")

      // Sending additional poll instruction (should still be busy)
      driver.push(Seq(txProto.tx(getStatus(1))))

      if (interrupt_en == 1) {
        while (!dut.module.io.interrupt.peek().litToBoolean) {
          clock.step()
          cycleCount += 1
        }
        clock.step() // Note: Interrupt and Busy are raised at the same time
        assert(!dut.module.io.busy.peek().litToBoolean, "Accelerator is still busy after interrupt was raised.")
      } else {
        val initData = if (destructive) stim._4.last else BigInt(0)
        while(!finishedWriting(slaveModel.state, stim._5, stim._4.length, initData, beatBytes)) {
          clock.step()
          cycleCount += 1
        }
        clock.step(5) // Few cycles delay for AccessAck to propagate back and busy to be de-asserted
        assert(!dut.module.io.busy.peek().litToBoolean, "Accelerator is still busy when data was written back.")
      }

      // Sending additional poll instruction (should be non-busy)
      driver.push(Seq(txProto.tx(getStatus(1))))
      clock.step(10)

      // Checking data result
      if (reuse_key) allPass &= checkResult(actualKeySize, prev_key, stim._4, stim._5, encrypt = encrypt, slaveModel.state, beatBytes)
      else allPass &= checkResult(actualKeySize, stim._2, stim._4, stim._5, encrypt = encrypt, slaveModel.state, beatBytes)

      // Checking poll responses (first 2 should be busy, last one should be non-busy)
      val resp = monitor.monitoredTransactions
      assert(resp.size == 3, s"Accelerator responded with ${resp.size} status messages (expected 3).")
      assert(resp(0).data.data.litValue() == 1, s"Accelerator responded non-busy for first status query (expected busy).")
      assert(resp(1).data.data.litValue() == 1, s"Accelerator responded non-busy for second status query (expected busy).")
      assert(resp(2).data.data.litValue() == 0, s"Accelerator responded busy for third status query (expected non-busy).")
      monitor.clearMonitoredTransactions()

      blocksProcessed += stim._4.length
    }

    println(s"====== :Performance stats: ======")
    println(s"Blocks Processed: $blocksProcessed")
    println(s"Cycles Elapsed: $cycleCount")
    println(s"Average Cycles/Block: ${cycleCount/blocksProcessed.toDouble}")
    allPass
  }

//  // Elaborate to see if anything structure-wise broke
//  it should "elaborate the accelerator" in {
//    val dut = LazyModule(new AESAccelStandaloneBlock(beatBytes))
//    // Requires verilator backend! (For verilog blackbox files)
//    test(dut.module).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { c =>
//      assert(true)
//    }
//  }

  it should "Test 128b AES Encryption" in {
    val dut = LazyModule(new AESAccelStandaloneBlock(beatBytes))
    test(dut.module).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { c =>
      val result = testAccelerator(dut, c.clock, keySize = 128, encdec = 1, interrupt = -1, rounds = 20, reuse_key_en = true)
      assert(result)
    }
  }

  it should "Test 128b AES Decryption" in {
    val dut = LazyModule(new AESAccelStandaloneBlock(beatBytes))
    test(dut.module).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { c =>
      val result = testAccelerator(dut, c.clock, keySize = 128, encdec = 0, interrupt = 0, rounds = 20, reuse_key_en = true)
      assert(result)
    }
  }

  it should "Test 256b AES Encryption" in {
    val dut = LazyModule(new AESAccelStandaloneBlock(beatBytes))
    test(dut.module).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { c =>
      val result = testAccelerator(dut, c.clock, keySize = 256, encdec = 1, interrupt = 0, rounds = 20, reuse_key_en = true)
      assert(result)
    }
  }

  it should "Test 256b AES Decryption" in {
    val dut = LazyModule(new AESAccelStandaloneBlock(beatBytes))
    test(dut.module).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { c =>
      val result = testAccelerator(dut, c.clock, keySize = 256, encdec = 0, interrupt = 0, rounds = 20, reuse_key_en = true)
      assert(result)
    }
  }

  it should "Test Mixed 128/256 AES Encryption/Decryption" in {
    val dut = LazyModule(new AESAccelStandaloneBlock(beatBytes))
    test(dut.module).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { c =>
      // Cannot reuse-key as keys may be different sizes
      val result = testAccelerator(dut, c.clock, keySize = -1, encdec = -1, interrupt = 0, rounds = 20, reuse_key_en = false)
      assert(result)
    }
  }


//  // Debug sanity test
//  // 128B Key:   256'h2b7e151628aed2a6abf7158809cf4f3c00000000000000000000000000000000
//  // plaintext:  128'h6bc1bee22e409f96e93d7e117393172a
//  // ciphertext: 128'h3ad77bb40d7a3660a89ecaf32466ef97
//  it should "debug AES sanity check" in {
//    val dut = LazyModule(new AESAccelStandaloneBlock(beatBytes))
//    test(dut.module).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { c =>
//      // RoCCCommand driver + RoCCResponse receiver
//      val driver = new DecoupledDriverMaster[RoCCCommand](c.clock, dut.module.io.cmd)
//      val txProto = new DecoupledTX(new RoCCCommand())
//      val monitor = new DecoupledMonitor[RoCCResponse](c.clock, dut.module.io.resp)
//      val receiver = new DecoupledDriverSlave[RoCCResponse](c.clock, dut.module.io.resp, 0)
//
//      // Mock Memory
//      val slaveFn = new TLMemoryModel(dut.to_mem.params)
//      val slaveModel = new TLDriverSlave(c.clock, dut.to_mem, slaveFn, TLMemoryModel.State.empty())
//      val slaveMonitor = new TLMonitor(c.clock, dut.to_mem)
//
//      val keyData = BigInt("2b7e151628aed2a6abf7158809cf4f3c00000000000000000000000000000000", 16)
//      var initMem: Map[WordAddr, BigInt] = Map ()
//      val keyDataRev = BigInt(keyData.toByteArray.reverse)
//      initMem = initMem + (0.toLong -> (keyDataRev & BigInt("1" * 128, 2)))
//      initMem = initMem + (1.toLong -> (keyDataRev >> 128))
//      val textData = BigInt("6bc1bee22e409f96e93d7e117393172a", 16)
//      initMem = initMem + (2.toLong -> BigInt(textData.toByteArray.reverse))
//      slaveModel.state = State.init(initMem, beatBytes)
//
//      val input = Seq(
//        txProto.tx(keyLoad128(0x0)),
//        txProto.tx(addrLoad(0x20, 0x30)),
//        txProto.tx(encBlock(1, 0))
//      )
//      driver.push(input)
//      c.clock.step(50)
//
//      while(!finishedWriting(slaveModel.state, 0x30, 1, 0, beatBytes)) {
//        c.clock.step()
//      }
//      c.clock.step(5) // Few cycles delay for AccessAck to propagate back and busy to be de-asserted
//      assert(!dut.module.io.busy.peek().litToBoolean, "Accelerator is still busy when data was written back.")
//
//      val actual = read(slaveModel.state.mem, 3.toLong, beatBytes, -1)
//      println(s"RESULT: ${actual.toString(16)}")
//      assert(actual == BigInt(Array(0.toByte) ++ BigInt("3ad77bb40d7a3660a89ecaf32466ef97", 16).toByteArray.takeRight(16).reverse))
//    }
//  }
}