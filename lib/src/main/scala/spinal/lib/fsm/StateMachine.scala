package spinal.lib.fsm

/**
 * Created by PIC32F_USER on 14/06/2016.
 */



import spinal.core._
import spinal.lib._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Created by PIC32F_USER on 14/06/2016.
 */

trait StateMachineAccessor{
  def setEntry(state : State) : Unit
  def goto(state : State) : Unit
  def add(state : State) : Int
  def add(stateMachine: StateMachine) : Unit
  def start() : Unit
  def exit() : Unit
}


class StateBoot(autoStart : Boolean)(implicit stateMachineAccessor : StateMachineAccessor) extends State{
  if(autoStart) {
    whenIsActive {
      stateMachineAccessor.start()
    }
  }
}

class StateMachineEnum extends SpinalEnum

  //TODO extends Area  and then it loop
class StateMachine extends Area with StateMachineAccessor{
  val enumDefinition = new StateMachineEnum
  var stateReg  = Reg(enumDefinition())
  var stateNext = enumDefinition()
  val wantExit = False
  var autoStart = true
  @dontName var parentStateMachine : StateMachine = null
  @dontName private val childStateMachines = ArrayBuffer[StateMachine]()
  val stateMachineToEnumElement = mutable.HashMap[StateMachine,enumDefinition.E]()
  @dontName val states = ArrayBuffer[State]()
  val stateToEnumElement = mutable.HashMap[State,enumDefinition.E]()
  @dontName var entryState : State = null
  def enumOf(state : State) = stateToEnumElement(state)
  def enumOf(stateMachine : StateMachine) = stateMachineToEnumElement(stateMachine)
  def build() : Unit = {
    childStateMachines.foreach(_.build())
    val stateBoot = new StateBoot(autoStart).setName("boot") //TODO

    for(state <- states){
      val enumElement = enumDefinition.newElement(state.getName())
      stateToEnumElement += (state -> enumElement)
    }
    for(child <- childStateMachines){
      val enumElement = enumDefinition.newElement(child.getName())
      stateMachineToEnumElement += (child -> enumElement)
    }

    stateReg init(enumOf(stateBoot))
    stateReg := stateNext

    val stateRegOneHotMap  = states.map(state => (state -> (stateReg === enumOf(state)))).toMap
    val stateNextOneHotMap = states.map(state => (state -> (stateNext === enumOf(state)))).toMap


    stateNext := stateReg
    switch(stateReg){
      for(state <- states){
        state match {
          case `stateBoot` => default {
            state.whenActiveTasks.foreach(_())
          }
          case _ => is(enumOf(state)) {
            state.whenActiveTasks.foreach(_())
          }
        }
      }
    }

    switch(stateNext){
      for(state <- states){
        state match {
          case `stateBoot` => default {
            state.whenIsNextTasks.foreach(_())
          }
          case _ => is(enumOf(state)) {
            state.whenIsNextTasks.foreach(_())
          }
        }
      }
    }


    for(state <- states){
      when(!stateRegOneHotMap(state) && stateNextOneHotMap(state)){
        state.onEntryTasks.foreach(_())
      }
      when(stateRegOneHotMap(state) && !stateNextOneHotMap(state)){
        state.onExitTasks.foreach(_())
      }
    }
  }

  Component.current.addPrePopTask(() => {
    if(parentStateMachine == null)
      build()
  })


  override def setEntry(state : State): Unit = {
    assert(entryState == null,"Entry point already set !")
    entryState = state
  }
  override def goto(state: State): Unit = stateNext := enumOf(state)

  override def add(state: State): Int = {
    states += state
    states.length-1
  }
  override def add(stateMachine : StateMachine) : Unit = {
    childStateMachines += stateMachine
    stateMachine.parentStateMachine = this
  }

  def start() : Unit = goto(entryState)
  def exit() : Unit = wantExit := True
  @dontName implicit val implicitFsm = this
}