/*
 * SpinalHDL
 * Copyright (c) Dolu, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */

package spinal.lib

import java.io.UTFDataFormatException
import java.nio.charset.Charset

import spinal.core._
import sun.text.normalizer.UTF16

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


object OHToUInt {
  def apply(bitVector: BitVector): UInt = apply(bitVector.toBools)

  def apply(bools: collection.IndexedSeq[Bool]): UInt = {
    val boolsSize = bools.size
    if (boolsSize < 2) return U(0)

    val retBitCount = log2Up(bools.size)
    val ret = Vec(Bool,retBitCount)

    for (retBitId <- 0 until retBitCount) {
      var bit: Bool = null
      for (boolsBitId <- 0 until boolsSize if ((boolsBitId >> retBitId) & 1) != 0) {
        if (bit != null)
          bit = bit | bools(boolsBitId)
        else
          bit = bools(boolsBitId)
      }
      ret(retBitId) := bit.dontSimplifyIt()
    }

    ret.toBits.toUInt
  }
}

object toGray {
  def apply(uint: UInt): Bits = {
    toBits((uint >> U(1)) ^ uint)
  }
}

object fromGray {
  def apply(gray: Bits): UInt = {
    val ret = UInt(widthOf(gray) bit)
    for (i <- 0 until widthOf(gray) - 1) {
      ret(i) := gray(i) ^ ret(i + 1)
    }
    ret.msb := gray.msb
    ret
  }
}

object adderAndCarry {
  def apply(left: UInt, right: UInt): (UInt, Bool) = {
    val temp = left.resize(left.getWidth + 1) + right.resize(right.getWidth + 1)
    return (temp.resize(temp.getWidth - 1), temp.msb)
  }
}

//This is a pure software, It can be used by a software driver to pack data
class BitAggregator {
  val elements = ArrayBuffer[(BigInt, Int)]()
  def clear = elements.clear()
  def add(valueParam: BigInt, bitCount: Int): Unit = elements += (valueParam -> bitCount)
  def add(valueParam: Boolean): Unit = if (valueParam) add(1, 1) else add(0, 1)

  def getWidth = elements.foldLeft(0)(_ + _._2)

  def toBytes: Seq[Byte] = {
    val elementsWidth = getWidth
    val bytes = new Array[Byte]((elementsWidth + 7) / 8)
    var byteId = 0
    var byteBitId = 0
    for (element <- elements) {
      var bitCount = element._2
      var value = (element._1 & (BigInt(1) << element._2) - 1) << byteBitId;
      while (bitCount != 0) {
        val bitToInsert = Math.min(bitCount, 8 - byteBitId);

        bytes(byteId) = (bytes(byteId) | value.toByte).toByte

        byteBitId += bitToInsert;
        if (byteBitId == 8) {
          byteBitId = 0;
          byteId += 1
        }
        value >>= 8;
        bitCount -= bitToInsert;
      }
    }

    bytes
  }

  override def toString: String = toBytes.map("%02X" format _).mkString(" ")
}

//object Flag{
//  def apply() = new Flag
//
//  implicit def implicitValue(f: Flag) = f.value
//}
//class Flag extends Area{
//  val value = False
//  def set = value := True
//}

object CounterFreeRun {
  def apply(stateCount: BigInt): Counter = {
    val c = new Counter(stateCount)
    c.increment()
    c
  }
}

object Counter {
  def apply(stateCount: BigInt): Counter = new Counter(stateCount)
  def apply(stateCount: BigInt, inc: Bool): Counter = {
    val counter = Counter(stateCount)
    when(inc) {
      counter.increment()
    }
    counter
  }
//  implicit def implicitValue(c: Counter) = c.value
}

class Counter(val stateCount: BigInt) extends ImplicitArea[UInt] {
  val willIncrement = False
  val willClear = False

  def clear(): Unit = willClear := True
  def increment(): Unit = willIncrement := True

  def ===(that: UInt): Bool = this.value === that
  def !==(that: UInt): Bool = this.value =/= that

  val valueNext = UInt(log2Up(stateCount) bit)
  val value = RegNext(valueNext) init(0)
  val willOverflowIfInc = value === stateCount - 1
  val willOverflow = willOverflowIfInc && willIncrement

  if (isPow2(stateCount)) {
    valueNext :~= value + toUInt(willIncrement)
  }
  else {
    when(willIncrement) {
      when(willOverflowIfInc) {
        valueNext := U(0)
      } otherwise {
        valueNext := value + U(1)
      }
    } otherwise {
      valueNext := value
    }
  }
  when(willClear) {
    valueNext := 0
  }

  override def implicitValue: UInt = this.value
}


object Timeout {
  def apply(limit: BigInt) = new Timeout(limit)
}

class Timeout(val limit: BigInt) extends ImplicitArea[Bool] {
  assert(limit > 1)

  val state = RegInit(False)
  val stateRise = False

  val counter = CounterFreeRun(limit)
  when(counter.willOverflow) {
    state := True
    stateRise := !state
  }

  def clear() : Unit = {
    counter.clear()
    state := False
    stateRise := False
  }

  override def implicitValue: Bool = state
}

object MajorityVote {
  def apply(that: BitVector): Bool = apply(that.toBools)
  def apply(that: collection.IndexedSeq[Bool]): Bool = {
    val size = that.size
    val trigger = that.size / 2 + 1
    var globalOr = False
    for (i <- BigInt(0) until (BigInt(1) << size)) {
      if (i.bitCount == trigger) {
        var localAnd = True
        for (bitId <- 0 until i.bitLength) {
          if (i.testBit(bitId)) localAnd &= that(bitId)
        }
        globalOr = globalOr | localAnd
      }
    }
    globalOr
  }
}

object SpinalMap {
  def apply[Key <: Data, Value <: Data](elems: Tuple2[() => Key, () => Value]*): SpinalMap[Key, Value] = {
    new SpinalMap(elems)
  }
}

class SpinalMap[Key <: Data, Value <: Data](pairs: Iterable[(() => Key, () => Value)]) {
  def apply(key: Key): Value = {
    val ret: Value = pairs.head._2()

    for ((k, v) <- pairs.tail) {
      when(k() === key) {
        ret := v()
      }
    }

    ret
  }
}


object latencyAnalysis {
  //Don't care about clock domain
  def apply(paths: Node*): Integer = {
    var stack = 0;
    for (i <- (0 to paths.size - 2)) {
      stack = stack + impl(paths(i), paths(i + 1))
    }
    stack
  }

  def impl(from: Node, to: Node): Integer = {
    val walked = mutable.Set[Node]()
    var pendingStack = mutable.ArrayBuffer[Node](to)
    var depth = 0;

    while (pendingStack.size != 0) {
      val iterOn = pendingStack
      pendingStack = new mutable.ArrayBuffer[Node](10000)
      for (start <- iterOn) {
        if (walk(start)) return depth;
      }
      depth = depth + 1
    }

    def walk(that: Node, depth: Integer = 0): Boolean = {
      if (that == null) return false
      if (walked.contains(that)) return false
      walked += that
      if (that == from)
        return true
      that match {
        case delay: SyncNode => {
          for (input <- delay.getAsynchronousInputs) {
            if (walk(input)) return true
          }
          pendingStack ++= delay.getSynchronousInputs
        }
        case _ => {
          for (input <- that.inputs) {
            if (walk(input)) return true
          }
        }
      }
      false
    }

    SpinalError("latencyAnalysis don't find any path")
    -1
  }
}


trait DataCarrier[T <: Data] {
  def fire: Bool
  def valid: Bool
  def data: T
  def freeRun(): this.type
}


object DelayEvent {
  def apply(event: Bool, t: Double, hz: Double): Bool = {
    DelayEvent(event, ((t - 100e-12) * hz).ceil.toInt)
  }

  def apply(event: Bool, cycle: BigInt): Bool = {
    if (cycle == 0) return event
    val run = RegInit(False)
    val counter = Counter(cycle)

    counter.increment()

    when(counter.willOverflow) {
      run := False
    }

    when(event) {
      run := True
      counter.clear()
    }

    return run && counter.willOverflow
  }

  def apply(event: Bool, cycle: UInt): Bool = {
    val ret = False
    val isDelaying = RegInit(False)
    val counterNext = cloneOf(cycle)
    val counter = RegNext(counterNext)
    val counterMatch = counterNext === cycle

    counterNext := counter + 1

    when(event) {
      counterNext := 0
      when(counterMatch) {
        isDelaying := False
        ret := True
      } otherwise {
        isDelaying := True
      }
    }.elsewhen(isDelaying) {
      when(counterMatch) {
        isDelaying := False
        ret := True
      }
    }

    ret
  }

}


class NoData extends Bundle {

}


class TraversableOncePimped[T <: Data](pimped: scala.collection.Iterable[T]) {
  def reduceBalancedSpinal(op: (T, T) => T): T = {
    reduceBalancedSpinal(op, (s,l) => s)
  }
  def reduceBalancedSpinal(op: (T, T) => T, levelBridge: (T, Int) => T): T = {
    def stage(elements: ArrayBuffer[T], level: Int): T = {
      if (elements.length == 1) return elements.head
      val stageLogic = new ArrayBuffer[T]()
      val logicCount = (elements.length + 1) / 2

      for (i <- 0 until logicCount) {
        if (i * 2 + 1 < elements.length)
          stageLogic += levelBridge(op(elements(i * 2), elements(i * 2 + 1)), level)
        else
          stageLogic += levelBridge(elements(i * 2), level)
      }
      stage(stageLogic, level + 1)

    }
    val array = ArrayBuffer[T]() ++ pimped
    assert(array.length >= 1)
    stage(array, 0)
  }


  def read(idx: UInt): T = {
    Vec(pimped)(idx)
  }
  def write(index: UInt, data: T): Unit = {
    read(index) := data
  }
  def apply(index: UInt): T = read(index)

  def sExists(condition: T => Bool): Bool = (pimped map condition).fold(False)(_ || _)
  def sContains(value: T) : Bool = sExists(_ === value)

  def sCount(condition: T => Bool): UInt = SetCount((pimped.map(condition)))
  def sCount(value: T ): UInt = sCount(_ === value)

  def sFindFirst(condition: T => Bool) : (Bool,UInt) = {
    val size = pimped.size
    val hits = pimped.map(condition(_))
    val hitValid = hits.reduceLeft(_ || _)
    val hitValue = PriorityMux(hits,(0 until size).map(U(_,log2Up(size) bit)))
    (hitValid,hitValue)
  }
}


object Delay {
  def apply[T <: Data](that: T, length: Int): T = {
    length match {
      case 0 => that
      case _ => Delay(RegNext(that), length - 1)
    }
  }
}


object Delays {
  def apply[T <: Data](that: T, length: Int): Vec[T] = {
    def builder(that: T, left: Int): List[T] = {
      left match {
        case 0 => that :: Nil
        case _ => that :: builder(RegNext(that), left - 1)
      }
    }
    Vec(builder(that, length))
  }
}


object SetCount
{
  def apply(in: Iterable[Bool]): UInt = {
    if (in.size == 0) {
      U(0)
    } else if (in.size == 1) {
      in.head.toUInt
    } else {
      (U(0,1 bit) ## apply(in.slice(0, in.size/2))).toUInt + apply(in.slice(in.size/2, in.size))
    }
  }
  def apply(in: Bits): UInt = apply((0 until in.getWidth).map(in(_)))
}

object ClearCount{
  def apply(in: Iterable[Bool]): UInt = SetCount(in.map(!_))
  def apply(in: Bits) : UInt = SetCount(~in)
}

class StringPimped(pimped : String){
  def toVecOfByte(encoding : String = "UTF-8") : Vec[Bits] = {
    Vec(pimped.getBytes(Charset.forName(encoding)).map(b => B(b.toInt, 8 bit)))
  }
}


object PriorityMux{
  def apply[T <: Data](in: Iterable[(Bool, T)]): T = {
    if (in.size == 1) {
      in.head._2
    } else {
      Mux(in.head._1, in.head._2, apply(in.tail)) //Inttelij right code marked red
    }
  }
  def apply[T <: Data](sel: Iterable[Bool], in: Iterable[T]): T = apply(sel zip in)
  def apply[T <: Data](sel: Bits, in: Iterable[T]): T = apply(sel.toBools.zip(in))
}