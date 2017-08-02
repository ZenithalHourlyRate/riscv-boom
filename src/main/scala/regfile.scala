//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Processor Datapath Register File
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Christopher Celio
// 2013 May 1


package boom

import Chisel._
import cde.Parameters

import scala.collection.mutable.ArrayBuffer

class RegisterFileReadPortIO(addr_width: Int, data_width: Int)(implicit p: Parameters) extends BoomBundle()(p)
{
   val addr = UInt(INPUT, addr_width)
   val data = UInt(OUTPUT, data_width)
   override def cloneType = new RegisterFileReadPortIO(addr_width, data_width)(p).asInstanceOf[this.type]
}

class RegisterFileWritePort(addr_width: Int, data_width: Int)(implicit p: Parameters) extends BoomBundle()(p)
{
   val addr = UInt(width = addr_width)
   val data = UInt(width = data_width)
   override def cloneType = new RegisterFileWritePort(addr_width, data_width)(p).asInstanceOf[this.type]
}


// utility function to turn ExeUnitResps to match the regfile's WritePort I/Os.
object WritePort
{
   def apply(enq: DecoupledIO[ExeUnitResp], addr_width: Int, data_width: Int)
   (implicit p: Parameters): DecoupledIO[RegisterFileWritePort] =
   {
      val wport = Wire(Decoupled(new RegisterFileWritePort(addr_width, data_width)))
      wport.valid := enq.valid
      wport.bits.addr := enq.bits.uop.pdst
      wport.bits.data := enq.bits.data
      enq.ready := wport.ready

      wport
   }
}


abstract class RegisterFile(
   num_registers: Int,
   num_read_ports: Int,
   num_write_ports: Int,
   register_width: Int,
   bypassable_array: Seq[Boolean]) // which write ports can be bypassed to the read ports?
   (implicit p: Parameters) extends BoomModule()(p)
{
   val io = new BoomBundle()(p)
   {
      val read_ports = Vec(num_read_ports, new RegisterFileReadPortIO(PREG_SZ, register_width))
      val write_ports = Vec(num_write_ports, Decoupled(new RegisterFileWritePort(PREG_SZ, register_width))).flip
   }

   private val rf_cost = (num_read_ports+num_write_ports)*(num_read_ports+2*num_write_ports)
   private val type_str = if (register_width == fLen+1) "Floating Point" else "Integer"
   override def toString: String =
      "\n   ==" + type_str + " Regfile==" +
      "\n   Num RF Read Ports     : " + num_read_ports +
      "\n   Num RF Write Ports    : " + num_write_ports +
      "\n   RF Cost (R+W)*(R+2W)  : " + rf_cost
}

// Combinational Register File. Provide read address and get data back on the same cycle.
class RegisterFileComb(
   num_registers: Int,
   num_read_ports: Int,
   num_write_ports: Int,
   register_width: Int,
   bypassable_array: Seq[Boolean])
   (implicit p: Parameters)
   extends RegisterFile(num_registers, num_read_ports, num_write_ports, register_width, bypassable_array)
{
   // --------------------------------------------------------------

   val regfile = Mem(num_registers, UInt(width=register_width))


   // --------------------------------------------------------------
   // Read ports.

   val read_data = Wire(Vec(num_read_ports, UInt(width = register_width)))

   for (i <- 0 until num_read_ports)
   {
      read_data(i) := Mux(io.read_ports(i).addr === UInt(0), UInt(0),
                                                             regfile(io.read_ports(i).addr))
   }


   // --------------------------------------------------------------
   // Bypass out of the ALU's write ports.

   require (bypassable_array.length == io.write_ports.length)

   if (bypassable_array.reduce(_||_))
   {
      val bypassable_wports = ArrayBuffer[DecoupledIO[RegisterFileWritePort]]()
      io.write_ports zip bypassable_array map { case (wport, b) => if (b) { bypassable_wports += wport} }

      for (i <- 0 until num_read_ports)
      {
         val bypass_ens = bypassable_wports.map(x => x.valid &&
                                                  x.bits.addr =/= UInt(0) &&
                                                  x.bits.addr === io.read_ports(i).addr)

         val bypass_data = Mux1H(Vec(bypass_ens), Vec(bypassable_wports.map(_.bits.data)))

         io.read_ports(i).data := Mux(bypass_ens.reduce(_|_), bypass_data, read_data(i))
      }
   }
   else
   {
      for (i <- 0 until num_read_ports)
      {
         io.read_ports(i).data := read_data(i)
      }
   }


   // --------------------------------------------------------------
   // Write ports.

   for (wport <- io.write_ports)
   {
      wport.ready := Bool(true)
      when (wport.valid && (wport.bits.addr =/= UInt(0)))
      {
         regfile(wport.bits.addr) := wport.bits.data
      }
   }
}


// Sequential Read Register File. Provide read address by end of cycle #0 and get data back on the next cycle #1.
class RegisterFileSeq(
   num_registers: Int,
   num_read_ports: Int,
   num_write_ports: Int,
   register_width: Int,
   bypassable_array: Seq[Boolean])
   (implicit p: Parameters)
   extends RegisterFile(num_registers, num_read_ports, num_write_ports, register_width, bypassable_array)
{

   // --------------------------------------------------------------

   val regfile = SeqMem(num_registers, UInt(width=register_width))


   // --------------------------------------------------------------
   // Read ports.

   val read_data = Wire(Vec(num_read_ports, UInt(width = register_width)))

   for (i <- 0 until num_read_ports)
   {
      read_data(i) :=
         Mux(RegNext(io.read_ports(i).addr === UInt(0)),
            UInt(0),
            regfile.read(io.read_ports(i).addr))
   }


   // --------------------------------------------------------------
   // Bypass out of the ALU's write ports.

   require (bypassable_array.length == io.write_ports.length)

   if (bypassable_array.reduce(_||_))
   {
      val bypassable_wports = ArrayBuffer[DecoupledIO[RegisterFileWritePort]]()
      io.write_ports zip bypassable_array map { case (wport, b) => if (b) { bypassable_wports += wport} }

      for (i <- 0 until num_read_ports)
      {
         val bypass_ens = bypassable_wports.map(x => x.valid &&
                                                  x.bits.addr =/= UInt(0) &&
                                                  x.bits.addr === RegNext(io.read_ports(i).addr))

         val bypass_data = Mux1H(Vec(bypass_ens), Vec(bypassable_wports.map(_.bits.data)))

         io.read_ports(i).data := Mux(bypass_ens.reduce(_|_), bypass_data, read_data(i))
      }
   }
   else
   {
      for (i <- 0 until num_read_ports)
      {
         io.read_ports(i).data := read_data(i)
      }
   }

   // --------------------------------------------------------------
   // Write ports.

   for (wport <- io.write_ports)
   {
      wport.ready := Bool(true)
      when (wport.valid && (wport.bits.addr =/= UInt(0)))
      {
         regfile.write(wport.bits.addr, wport.bits.data)
      }
   }
}

class RegisterFileSeq_i(
   num_registers: Int,
   num_read_ports: Int,
   num_write_ports: Int,
   register_width: Int,
   bypassable_array: Seq[Boolean])
   (implicit p: Parameters)
   extends RegisterFile(num_registers, num_read_ports, num_write_ports, register_width, bypassable_array)
{

   // --------------------------------------------------------------

   val regfile = Module(new RegisterFileArray(num_registers, num_read_ports, num_write_ports, register_width))

  // Decode addr
   val waddr_OH = Wire(Vec(num_write_ports, UInt(width = num_registers))) 
   val raddr_OH = Wire(Vec(num_read_ports, UInt(width = num_registers))) 
   val write_select_OH = Wire(Vec(num_registers, UInt(width = num_write_ports)))

   for (i <-0 until num_write_ports) {
      regfile.io.WD(i) := io.write_ports(i).bits.data
      waddr_OH(i) := UIntToOH(io.write_ports(i).bits.addr)
   }
   for (i <-0 until num_read_ports) {
      io.read_ports(i).data := regfile.io.RD(i)
      raddr_OH(i) := UIntToOH(io.read_ports(i).addr)
   }
      
   for (i <- 0 until num_registers) {
      regfile.io.OE(i) := Cat(raddr_OH(5)(i), raddr_OH(4)(i), raddr_OH(3)(i), raddr_OH(2)(i), raddr_OH(1)(i), raddr_OH(0)(i))
      write_select_OH(i) := Cat(waddr_OH(2)(i)&&io.write_ports(2).valid, waddr_OH(1)(i)&&io.write_ports(1).valid, waddr_OH(0)(i)&&io.write_ports(0).valid)
      regfile.io.WE(i) := write_select_OH(i).orR 
      regfile.io.WS(i) := OHToUInt(write_select_OH(i))
   }

  //Need to handle bypass and read address = 0 
}

class RegisterFileArray( 
   num_registers: Int,
   num_read_ports: Int,
   num_write_ports: Int,
   register_width: Int) extends BlackBox {
  val io = new Bundle {
    val clock = Clock(INPUT)
    val WE = UInt(INPUT, width = num_registers)
    val WD = Vec(num_write_ports, UInt(INPUT, register_width))
    val RD = Vec(num_read_ports, UInt(OUTPUT, register_width))
    val WS = Vec(num_registers, UInt(INPUT, register_width))
    val OE = Vec(num_registers, UInt(INPUT, num_read_ports))  
  }
}
