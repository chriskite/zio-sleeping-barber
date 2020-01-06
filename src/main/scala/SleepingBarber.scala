import BarberShop._
import zio.console.{Console, putStrLn}
import zio.duration._
import zio._
import zio.clock.Clock

class BarberShop(waitingRoom: WaitingRoom, barberChair: BarberChair) {
  def waitOrLeave(customer: Customer): URIO[Console, Boolean] =
    for {
      accepted <- waitingRoom.offer(customer) // try to enter the waiting room
      result <- if (accepted)
        customer.isReady.await // ready for the barber's chair
      else
        putStrLn("Waiting room was full, leaving") *> IO.succeed(false) // waiting room was full
    } yield result

  def wakeBarber: URIO[Console, Boolean] =
    barberChair.modify {
      case Empty | BarberInChair =>
        for {
          _ <- putStrLn("Waking barber")
        } yield (true, Empty)
      case _ =>
        IO.dieMessage("A customer is in the chair, can't wake the barber")
    }

  def sitInChair(customer: Customer): URIO[Console, Boolean] =
    barberChair.modify {
      case Empty =>
        for {
          _ <- putStrLn(s"Customer ${customer.id} sat in chair")
        } yield (true, LongHairCustomerInChair)
      case _ => IO.dieMessage("Chair isn't empty, can't sit in it")
    } *> customer.isSeated.succeed(true)

  def cutHair(customer: Customer): URIO[Console with Clock, Boolean] =
    barberChair.modify {
      case LongHairCustomerInChair =>
        for {
          _ <- ZIO.sleep(5.seconds) // simulate cutting hair for some time
          _ <- putStrLn("Barber cut hair")
        } yield (true, ShortHairCustomerInChair)
      case _ => IO.dieMessage("No LongHairCustomer in chair, can't cut hair")
    } *> customer.isHairCut.succeed(true)

  def napInChair: URIO[Console, Boolean] =
    barberChair.modify {
      case Empty =>
        for {
          _ <- putStrLn("Barber napping in chair")
        } yield (true, BarberInChair)
      case _ => IO.dieMessage("Chair isn't empty, can't nap")
    }

  def waitForCustomer: UIO[Customer] =
    for {
      customer <- waitingRoom.take
      _ <- customer.isReady.succeed(true)
    } yield customer

  def leaveChair(customer: Customer): URIO[Console, Boolean] =
    barberChair.modify {
      case ShortHairCustomerInChair =>
        for {
          _ <- putStrLn(s"Customer ${customer.id} left the chair")
        } yield (true, Empty)
      case _ => IO.dieMessage("No ShortHairCustomer in chair, can't leave")
    } *> customer.isGone.succeed(true)

  def barberTask: URIO[Console with Clock, Boolean] = {
    val loop =
      for {
        isCustomerWaiting <- waitingRoom.size.map(_ >= 1)
        _ <- if (isCustomerWaiting) IO.unit else napInChair
        customer <- waitForCustomer
        _ <- customer.isSeated.await
        _ <- cutHair(customer)
        _ <- customer.isGone.await
      } yield ()

    loop.forever
  }

  def customerTask(customer: Customer): URIO[Console, Unit] =
    for {
      _ <- putStrLn(s"Customer ${customer.id} trying to enter waiting room")
      accepted <- waitOrLeave(customer)
      _ <- if (accepted) {
        for {
          _ <- wakeBarber
          _ <- sitInChair(customer)
          _ <- customer.isHairCut.await
          _ <- leaveChair(customer)
        } yield ()
      } else Task.unit
    } yield ()

  def makeCustomer(id: Int): UIO[Customer] =
    for {
      isReady <- Promise.make[Nothing, Boolean]
      isSeated <- Promise.make[Nothing, Boolean]
      isHairCut <- Promise.make[Nothing, Boolean]
      isGone <- Promise.make[Nothing, Boolean]
    } yield Customer(id, isReady, isSeated, isHairCut, isGone)

  def simulation: URIO[Console with Clock, Unit] =
    for {
      barber <- barberTask.fork
      customers <- URIO.traverse((1 to 10).toList)(
        i => (makeCustomer(i) >>= customerTask).delay(1.second * i).fork
      )
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

  case class Customer(id: Int,
                      isReady: Promise[Nothing, Boolean],
                      isSeated: Promise[Nothing, Boolean],
                      isHairCut: Promise[Nothing, Boolean],
                      isGone: Promise[Nothing, Boolean])

  type BarberChair = RefM[ChairState]
  type WaitingRoom = Queue[Customer]

  def apply(numWaitingRoomChairs: Int): UIO[BarberShop] =
    for {
      waitingRoom <- Queue.dropping[Customer](numWaitingRoomChairs)
      barberChair <- RefM.make[ChairState](Empty)
    } yield new BarberShop(waitingRoom, barberChair)
}

object SleepingBarber extends zio.App {
  override def run(args: List[String]) = {
    val program = for {
      shop <- BarberShop(3)
      _ <- shop.simulation
    } yield ()

    program.as(0)
  }
}
