import BarberShop._
import zio.console.putStrLn
import zio.{Promise, Queue, Ref, Semaphore, Task, UIO, URIO, ZIO}
import zio.duration._

class BarberShop(waitingRoom: WaitingRoom, barberChair: BarberChair) {
  def waitOrLeave(customer: Customer)  = for {
    accepted <- waitingRoom.offer(customer) // try to enter the waiting room
    result <- if(accepted)
      customer.isReady.await // ready for the barber's chair
    else
      putStrLn("Waiting room was full, leaving") *> UIO.succeed(false) // waiting room was full
  } yield result

  def wakeBarber = barberChair.modify {
    case Empty | BarberInChair => (true, Empty)
    case _ => throw new Exception("A customer is in the chair, can't wake the barber")
  } *> putStrLn("Woke the barber")

  def sitInChair(customer: Customer) = barberChair.modify {
    case Empty => (true, LongHairCustomerInChair)
    case _ => throw new Exception("Chair isn't empty, can't sit in it")
  } *> customer.isSeated.succeed(true) *> putStrLn(s"Customer ${customer.id} sat in chair")

  def cutHair(customer: Customer) = ZIO.sleep(5.seconds) *> barberChair.modify {
    case LongHairCustomerInChair =>
      (true, ShortHairCustomerInChair)
    case _ => throw new Exception("No LongHairCustomer in chair, can't cut hair")
  } *> customer.isHairCut.succeed(true) *> putStrLn("Barber cut hair")

  def napInChair = barberChair.modify {
    case Empty => (true, BarberInChair)
    case _ => throw new Exception("Chair isn't empty, can't nap")
  } *> putStrLn("Barber napping in chair")

  def waitForCustomer = for {
    customer <- waitingRoom.take
    _ <- customer.isReady.succeed(true)
  } yield customer

  def leaveChair(customer: Customer) = barberChair.modify {
    case ShortHairCustomerInChair => (true, Empty)
    case _ => throw new Exception("No ShortHairCustomer in chair, can't leave")
  } *> customer.isGone.succeed(true) *> putStrLn(s"Customer ${customer.id} left the chair")

  def barberTask = {
    def loop = for {
      customerWaiting <- waitingRoom.poll
      _ <- if(customerWaiting.isDefined) Task.unit else napInChair
      customer <- waitForCustomer
      _ <- customer.isSeated.await
      _ <- cutHair(customer)
      _ <- customer.isGone.await
    } yield ()

    loop.forever
  }

  def customerTask(customer: Customer) = for {
      _ <- putStrLn(s"Customer ${customer.id} trying to enter waiting room")
      accepted <- waitOrLeave(customer)
      _ <- if(accepted) {
        for {
          _ <- wakeBarber
          _ <- sitInChair(customer)
          _ <- customer.isHairCut.await
          _ <- leaveChair(customer)
        } yield ()
      } else Task.unit
    } yield ()

  def makeCustomer(id: Int) = for {
    isReady <- Promise.make[Nothing, Boolean]
    isSeated <- Promise.make[Nothing, Boolean]
    isHairCut <- Promise.make[Nothing, Boolean]
    isGone <- Promise.make[Nothing, Boolean]
  } yield Customer(id, isReady, isSeated, isHairCut, isGone)

  def simulation = for {
    barber <- barberTask.fork
    customers <- URIO.traverse((1 to 1000).toList)(i => (makeCustomer(i) >>= customerTask).delay(1.second * i).fork)
    _ <- ZIO.traverse(customers)(_.join)
    _ <- barber.join
  } yield ()
}

object BarberShop {
  sealed trait ChairState
  final case object Empty extends ChairState
  final case object BarberInChair extends ChairState
  final case object LongHairCustomerInChair extends ChairState
  final case object ShortHairCustomerInChair extends ChairState

  case class Customer(id: Int, isReady: Promise[Nothing, Boolean], isSeated: Promise[Nothing, Boolean], isHairCut: Promise[Nothing, Boolean], isGone: Promise[Nothing, Boolean])

  type BarberChair = Ref[ChairState]
  type WaitingRoom = Queue[Customer]

  def apply(numWaitingRoomChairs: Int) = for {
    waitingRoom <- Queue.dropping[Customer](numWaitingRoomChairs)
    barberChair <- Ref.make[ChairState](Empty)
  } yield new BarberShop(waitingRoom, barberChair)
}

object SleepingBarber extends zio.App{
  override def run(args: List[String]) = {
    val program = for {
      shop <- BarberShop(3)
      _ <- shop.simulation
    } yield ()

    program.as(0)
  }
}
