package gemmini

import chisel3._
import chisel3.util._
import GemminiISA._
import Util._
import freechips.rocketchip.config.Parameters
//have counter 1st 2nd 3rd... row

// TODO this is almost a complete copy of LoadController. We should combine them into one class
// TODO deal with errors when reading scratchpad responses
class StoreController[T <: Data : Arithmetic](config: GemminiArrayConfig[T], coreMaxAddrBits: Int, local_addr_t: LocalAddr)
                     (implicit p: Parameters) extends Module {

  import config._

  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new GemminiCmdWithDeps(rob_entries)))

    // val dma = new ScratchpadWriteMemIO(sp_banks, sp_bank_entries, acc_rows)
    val dma = new ScratchpadWriteMemIO(local_addr_t)

    val completed = Decoupled(UInt(log2Up(rob_entries).W))

    val busy = Output(Bool())
  })

  val waiting_for_command :: waiting_for_dma_req_ready :: sending_rows :: Nil = Enum(3)
  val control_state = RegInit(waiting_for_command)

  val stride = RegInit((sp_width / 8).U(coreMaxAddrBits.W))
  val block_rows = meshRows * tileRows
  //  val row_counter = RegInit(0.U(log2Ceil(block_rows).W))
  //  val pool_row = RegInit(0.U(log2Ceil(block_rows).W))
  val out_width = RegInit(0.U(7.W))
  val row_counter = RegInit(0.U((2 * out_width.getWidth).W))
  val pool_row = RegInit(0.U((2 * out_width.getWidth).W))


  val cmd = Queue(io.cmd, st_queue_length) //all the rocc commends into store controller
  val vaddr = cmd.bits.cmd.rs1
  val localaddr = cmd.bits.cmd.rs2.asTypeOf(local_addr_t)
  val config_stride = cmd.bits.cmd.rs2(31, 0)
  // Seah: implement pool_stride here (config_mvout)
  //  val pool_en = RegInit(false.B)
  val pool_stride = RegInit(0.U(4.W))
  //  val out_width = RegInit(0.U(6.W))
  val pool_window = RegInit(0.U(4.W))
  val DoConfig = cmd.bits.cmd.inst.funct === CONFIG_CMD
  when(DoConfig) {
    pool_stride := cmd.bits.cmd.rs2(39, 36)
    pool_window := cmd.bits.cmd.rs2(35, 32)
    out_width := cmd.bits.cmd.rs2(46, 40) // if pooling unable, use as valid rows
    //    pool_en := cmd.bits.cmd.rs2(63)
  }
  /*
  val pool_stride = cmd.bits.cmd.rs2(39, 36)
  val pool_window = cmd.bits.cmd.rs2(35, 32)
  val out_width = cmd.bits.cmd.rs2(45, 40)
  val pool_en = cmd.bits.cmd.rs2(63)
  */

  val mstatus = cmd.bits.cmd.status

  val localaddr_plus_row_counter = WireInit(localaddr + row_counter) // compute spad address
  //Seah: change for pooling address
  when(pool_stride =/= 0.U) {
    localaddr_plus_row_counter := localaddr + pool_row
  }

  io.busy := cmd.valid
  //Seah: count matrix multiplication time
  val (mvout_time, mvout_time_wrap) = Counter(io.busy, 1024)
  val mvout_counter = WireInit(mvout_time)
  dontTouch(mvout_counter)


  //  val DoConfig = cmd.bits.cmd.inst.funct === CONFIG_CMD
  val DoStore = !DoConfig // TODO change this if more commands are added

  cmd.ready := false.B

  // Command tracker instantiation
  val nCmds = 2 // TODO make this a config parameter

  val deps_t = new Bundle {
    val rob_id = UInt(log2Up(rob_entries).W)
  }

  //val cmd_tracker = Module(new DMAReadCommandTracker(nCmds, block_rows, deps_t))
  val cmd_tracker = Module(new DMAReadCommandTracker(nCmds, 1 << (2*out_width.getWidth), deps_t))

  //Seah: pooling variables
  val store_en = WireInit(false.B)
  dontTouch(store_en)
  val pool_first_row = RegInit(true.B)
  val final_row = RegInit(false.B) //wire or register?
  val counter = RegInit(0.U((2 * pool_row.getWidth).W))
  val block = RegInit(0.U((pool_row.getWidth).W))

  val round_counter = RegInit(0.U((2 * pool_window.getWidth).W))
  val round_2_counter = RegInit(0.U((pool_window.getWidth).W))
  val round_row_counter = RegInit(0.U((out_width.getWidth).W))

  // DMA IO wiring
  io.dma.req.valid := (control_state === waiting_for_command && cmd.valid && DoStore && cmd_tracker.io.alloc.ready) ||
    control_state === waiting_for_dma_req_ready || (control_state === sending_rows && counter =/= 0.U)
  //    (control_state === sending_rows && row_counter =/= 0.U)
  io.dma.req.bits.vaddr := vaddr + row_counter * stride
  io.dma.req.bits.laddr := localaddr_plus_row_counter
  io.dma.req.bits.status := mstatus


  // Command tracker IO
  cmd_tracker.io.alloc.valid := control_state === waiting_for_command && cmd.valid && DoStore
  cmd_tracker.io.alloc.bits.bytes_to_read := Mux(pool_stride =/= 0.U, (1.U + (out_width - pool_window) / pool_stride) * (1.U + (out_width - pool_window) / pool_stride), out_width)
  cmd_tracker.io.alloc.bits.tag.rob_id := cmd.bits.rob_id
  cmd_tracker.io.request_returned.valid := io.dma.resp.fire() // TODO use a bundle connect
  cmd_tracker.io.request_returned.bits.cmd_id := io.dma.resp.bits.cmd_id // TODO use a bundle connect
  cmd_tracker.io.request_returned.bits.bytes_read := 1.U
  cmd_tracker.io.cmd_completed.ready := io.completed.ready

  val cmd_id = RegEnableThru(cmd_tracker.io.alloc.bits.cmd_id, cmd_tracker.io.alloc.fire()) // TODO is this really better than a simple RegEnable?
  io.dma.req.bits.cmd_id := cmd_id

  io.completed.valid := cmd_tracker.io.cmd_completed.valid
  io.completed.bits := cmd_tracker.io.cmd_completed.bits.tag.rob_id

  /*
  io.completed.valid := false.B
  io.completed.bits := cmd.bits.rob_id
  */

  //Seah: implement pooling related features here
  // Row counter
  when(!io.dma.req.valid){ //reset (padding -> unpadding rows only)
    counter := 0.U
    round_counter := 0.U
    round_2_counter := 0.U
    final_row := false.B
    row_counter := 0.U
    pool_row := 0.U
    round_row_counter := 0.U
    store_en := false.B
  }.elsewhen(io.dma.req.fire()) { //receive there is valid entries left from spad
    //row_counter := wrappingAdd(row_counter, 1.U, block_rows)
    //wrapingAdd: add 1 to row_counter, return 0 when become equal to block_row
    //when(pool_en) {
    when(pool_stride === 0.U) { //pool_stride = 0 means not do pooling
      pool_first_row := true.B
      store_en := true.B
      when(final_row){ //already have reached final unpadded row
        store_en := false.B
        counter := 0.U
        round_counter := 0.U
        round_2_counter := 0.U
        round_row_counter := 0.U
        row_counter := 0.U
        pool_row := 0.U
  //    }.elsewhen((counter + 1.U) % out_width === 0.U) {
      }.elsewhen((counter + 1.U) === out_width ) {
        final_row := true.B
      }.otherwise{
        counter :=  counter + 1.U
        round_counter := round_counter + 1.U
        round_2_counter := round_2_counter + 1.U
        row_counter := row_counter + 1.U
        pool_row := pool_row + 1.U
        round_row_counter := round_row_counter + 1.U
      }
      //pool_row := wrappingAdd(pool_row, 1.U, out_width)
     // row_counter := wrappingAdd(row_counter, 1.U, out_width)
    //counter := wrappingAdd(counter, 1.U, block_rows*5)//how?
    //    }.elsewhen(pool_row === block_rows.asUInt - 1.U) { //block_rows = 16
  }.elsewhen(pool_row === out_width * out_width - 1.U) {
    pool_first_row := true.B
    store_en := true.B //is it?
    pool_row := 0.U
      round_row_counter := 0.U
    counter := 0.U
      round_2_counter := 0.U
      round_counter := 0.U
    //row_counter := row_counter + 1.U
    row_counter := 0.U
    block := 0.U
      //final_row := true.B
  }.otherwise {
    //when((counter + 1.U) % (pool_window * pool_window) === 0.U) { //one pool finished
      when(round_counter + 1.U === (pool_window * pool_window)) { //one pool finished
        counter := counter + 1.U
        round_counter := round_counter - pool_window*pool_window + 1.U
        round_2_counter := round_2_counter - pool_window + 1.U
        pool_first_row := true.B //register
        store_en := true.B //wire
        row_counter := row_counter + 1.U //increment row counter for next address (not this)
        //when((pool_row + 1.U) % out_width === 0.U) { //one whole output row pooling out is done
        when(round_row_counter + pool_window === out_width) { //one whole output row pooling out is done
          pool_row := pool_stride * (block + 1.U) * out_width //move on to next row block
          round_row_counter := 0.U
         when((1.U + counter) =/= 0.U) {
          block := block + 1.U
         }.otherwise {
          block := 0.U
         }
        }.otherwise { // not done yet
        //pool_row := pool_row - pool_window * pool_window + pool_stride - 1.U
        pool_row := pool_row - (pool_window - 1.U) * out_width + pool_stride - pool_window + 1.U
          //round_row_counter := round_row_counter + pool_stride - pool_window + 1.U
          round_row_counter := round_row_counter + pool_stride
        }
    //}.elsewhen((counter + 1.U) % pool_window === 0.U) { //reading one row finished
      }.elsewhen(round_2_counter + 1.U === pool_window) {
        pool_row := pool_row + out_width - pool_window + 1.U
        //round_row_counter := round_row_counter + 1.U
        counter := counter + 1.U
        round_2_counter := round_2_counter - pool_window + 1.U
        round_counter := round_counter + 1.U
        pool_first_row := false.B
        store_en := false.B
      }.otherwise {
        pool_first_row := false.B
        store_en := false.B
        counter := counter + 1.U
        round_counter := round_counter + 1.U
        round_2_counter := round_2_counter + 1.U
        pool_row := pool_row + 1.U
        //round_row_counter := round_row_counter + 1.U
      }
  }
}

  //io.dma.req.bits.store_en := store_en
  //io.dma.req.bits.pool_first_row := pool_first_row


  // Control logic
  switch (control_state) {
    is (waiting_for_command) {
      when (cmd.valid) {
        when(DoConfig) {
          stride := config_stride
          cmd.ready := true.B
        }.elsewhen(DoStore && cmd_tracker.io.alloc.fire()) {
          control_state := Mux(io.dma.req.fire(), sending_rows, waiting_for_dma_req_ready)
        }
      }
    }
    is (waiting_for_dma_req_ready) {
      when (io.dma.req.fire()) {
        control_state := sending_rows
      }
    }

    is (sending_rows) {
      //val last_row = counter === 0.U || (row_counter === (block_rows-1).U && io.dma.req.fire())
      val last_row = counter === 0.U || (row_counter === out_width*out_width - 1.U && io.dma.req.fire())

      // io.completed.valid := last_row

      // when (io.completed.fire()) {
      when (last_row) {
        control_state := waiting_for_command
        cmd.ready := true.B
      }
    }
  }
  io.dma.req.bits.store_en := store_en
  io.dma.req.bits.pool_first_row := pool_first_row
}
