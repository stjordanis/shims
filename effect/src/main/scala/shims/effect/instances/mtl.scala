/*
 * Copyright 2018 Daniel Spiewak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package shims.effect.instances

import cats.{Functor, Monad}
import cats.effect.{
  Async,
  Bracket,
  CancelToken,
  Concurrent,
  ConcurrentEffect,
  ContextShift,
  Effect,
  ExitCase,
  Fiber,
  IO,
  Sync,
  SyncIO
}
import cats.syntax.all._

import scalaz.{\/, -\/, \/-, EitherT, IndexedStateT, Kleisli, OptionT, StateT}

import shims.AsSyntaxModule
import shims.conversions.{EitherConverters, MonadConversions, MonadErrorConversions}

import scala.concurrent.ExecutionContext

trait MTLBracket
    extends MonadErrorConversions
    with EitherConverters
    with AsSyntaxModule {

  implicit def scalazKleisliBracket[F[_]: Bracket[?[_], E], R, E]: Bracket[Kleisli[F, R, ?], E] =
    new KleisliBracket[F, R, E] { def F = Bracket[F, E] }

  protected[this] trait KleisliBracket[F[_], R, E] extends Bracket[Kleisli[F, R, ?], E] {
    protected implicit def F: Bracket[F, E]

    def pure[A](x: A): Kleisli[F, R, A] =
      Kleisli.kleisliApplicative[F, R].point(x)

    def handleErrorWith[A](fa: Kleisli[F, R, A])(f: E => Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli.kleisliMonadError[F, E, R].handleError(fa)(f)

    def raiseError[A](e: E): Kleisli[F, R, A] =
      Kleisli.kleisliMonadError[F, E, R].raiseError(e)

    def bracketCase[A, B](
        acquire: Kleisli[F, R, A])(
        use: A => Kleisli[F, R, B])(
        release: (A, ExitCase[E]) => Kleisli[F, R, Unit])
        : Kleisli[F, R, B] = {

      Kleisli { r =>
        F.bracketCase(acquire.run(r))(a => use(a).run(r)) { (a, br) =>
          release(a, br).run(r)
        }
      }
    }

    def flatMap[A, B](fa: Kleisli[F, R, A])(f: A => Kleisli[F, R, B]): Kleisli[F, R, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => Kleisli[F, R, Either[A, B]]): Kleisli[F, R, B] =
      Kleisli.kleisliBindRec[F, R].tailrecM(f.andThen(_.map(_.asScalaz)))(a)
  }
}

trait MTLSync extends MTLBracket {

  implicit def scalazOptionTSync[F[_]: Sync]: Sync[OptionT[F, ?]] =
    new OptionTSync[F] { def F = Sync[F] }

  implicit def scalazKleisliSync[F[_]: Sync, R]: Sync[Kleisli[F, R, ?]] =
    new KleisliSync[F, R] { def F = Sync[F] }

  implicit def scalazEitherTSync[F[_]: Sync, L]: Sync[EitherT[F, L, ?]] =
    new EitherTSync[F, L] { def F = Sync[F] }

  implicit def scalazStateTSync[F[_]: Sync, S]: Sync[StateT[F, S, ?]] =
    new StateTSync[F, S] { def F = Sync[F] }

  protected[this] trait OptionTSync[F[_]] extends Sync[OptionT[F, ?]] {
    protected implicit def F: Sync[F]

    def pure[A](x: A): OptionT[F, A] =
      OptionT.optionTMonadPlus[F].point(x)

    def handleErrorWith[A](fa: OptionT[F, A])(f: Throwable => OptionT[F, A]): OptionT[F, A] =
      OptionT.optionTMonadError[F, Throwable].handleError(fa)(f)

    def raiseError[A](e: Throwable): OptionT[F, A] =
      OptionT.optionTMonadError[F, Throwable].raiseError(e)

    def bracketCase[A, B](
        acquire: OptionT[F, A])(
        use: A => OptionT[F, B])(
        release: (A, ExitCase[Throwable]) => OptionT[F, Unit])
        : OptionT[F, B] = {

      OptionT(F.bracketCase(acquire.run) {
        case Some(a) => use(a).run
        case None => F.pure[Option[B]](None)
      } {
        case (Some(a), br) => release(a, br).run.map(_ => ())
        case _ => F.unit
      })
    }

    def flatMap[A, B](fa: OptionT[F, A])(f: A => OptionT[F, B]): OptionT[F, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => OptionT[F, Either[A, B]]): OptionT[F, B] =
      OptionT.optionTBindRec[F].tailrecM[A, B](a => f(a).map(_.asScalaz))(a)

    def suspend[A](thunk: => OptionT[F, A]): OptionT[F, A] =
      OptionT(F.suspend(thunk.run))

    override def uncancelable[A](fa: OptionT[F, A]): OptionT[F, A] =
      OptionT(F.uncancelable(fa.run))
  }

  protected[this] trait KleisliSync[F[_], R]
      extends KleisliBracket[F, R, Throwable]
      with Sync[Kleisli[F, R, ?]] {

    protected implicit def F: Sync[F]

    override def handleErrorWith[A](fa: Kleisli[F, R, A])(f: Throwable => Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli(r => F.suspend(F.handleErrorWith(fa.run(r))(e => f(e).run(r))))

    override def flatMap[A, B](fa: Kleisli[F, R, A])(f: A => Kleisli[F, R, B]): Kleisli[F, R, B] =
      Kleisli(r => F.suspend(fa.run(r).flatMap(f.andThen(_.run(r)))))

    def suspend[A](thunk: => Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli(r => F.suspend(thunk.run(r)))

    override def uncancelable[A](fa: Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli(r => F.suspend(F.uncancelable(fa.run(r))))
  }

  protected[this] trait EitherTSync[F[_], L] extends Sync[EitherT[F, L, ?]] {
    protected implicit def F: Sync[F]

    def pure[A](x: A): EitherT[F, L, A] = EitherT.pure[F, L, A](x)

    def handleErrorWith[A](fa: EitherT[F, L, A])(f: Throwable => EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT.eitherT(F.handleErrorWith(fa.run)(f.andThen(_.run)))

    def raiseError[A](e: Throwable): EitherT[F, L, A] =
      EitherT.rightT(F.raiseError(e))

    def bracketCase[A, B](
        acquire: EitherT[F, L, A])(
        use: A => EitherT[F, L, B])(
        release: (A, ExitCase[Throwable]) => EitherT[F, L, Unit])
        : EitherT[F, L, B] = {

      EitherT(F.bracketCase(acquire.run) {
        case \/-(a) => use(a).run
        case e @ -\/(_) => F.pure(e: \/[L, B])
      } { (ea, br) =>
        ea match {
          case \/-(a) => release(a, br).run.void
          case -\/(_) => F.unit // nothing to release
        }
      })
    }

    def flatMap[A, B](fa: EitherT[F, L, A])(f: A => EitherT[F, L, B]): EitherT[F, L, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => EitherT[F, L, Either[A, B]]): EitherT[F, L, B] =
      EitherT.eitherTBindRec[F, L].tailrecM(f.andThen(_.map(_.asScalaz)))(a)

    def suspend[A](thunk: => EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT.eitherT(F.suspend(thunk.run))

    override def uncancelable[A](fa: EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT.eitherT(F.uncancelable(fa.run))
  }

  protected[this] trait StateTSync[F[_], S] extends Sync[StateT[F, S, ?]] {
    protected implicit def F: Sync[F]

    def pure[A](x: A): StateT[F, S, A] = StateT.stateT(x)

    def handleErrorWith[A](fa: StateT[F, S, A])(f: Throwable => StateT[F, S, A]): StateT[F, S, A] =
      StateT(s => F.handleErrorWith(fa.run(s))(e => f(e).run(s)))

    def raiseError[A](e: Throwable): StateT[F, S, A] =
      StateT.liftM(F.raiseError(e))

    def bracketCase[A, B](acquire: StateT[F, S, A])
      (use: A => StateT[F, S, B])
      (release: (A, ExitCase[Throwable]) => StateT[F, S, Unit]): StateT[F, S, B] = {

      StateT { startS =>
        F.bracketCase(acquire.run(startS)) { case (s, a) =>
          use(a).run(s)
        } { case ((s, a), br) =>
          release(a, br).run(s).void
        }
      }
    }

    override def uncancelable[A](fa: StateT[F, S, A]): StateT[F, S, A] =
      fa.mapT(F.uncancelable)

    def flatMap[A, B](fa: StateT[F, S, A])(f: A => StateT[F, S, B]): StateT[F, S, B] =
      fa.flatMap(f)

    // overwriting the pre-existing one, since flatMap is guaranteed stack-safe
    def tailRecM[A, B](a: A)(f: A => StateT[F, S, Either[A, B]]): StateT[F, S, B] =
      IndexedStateT.stateTBindRec[S, F].tailrecM(f.andThen(_.map(_.asScalaz)))(a)

    def suspend[A](thunk: => StateT[F, S, A]): StateT[F, S, A] =
      StateT(s => F.suspend(thunk.run(s)))
  }
}

trait MTLAsync extends MTLSync {

  implicit def scalazOptionTAsync[F[_]: Async]: Async[OptionT[F, ?]] =
    new OptionTAsync[F] { def F = Async[F] }

  implicit def scalazKleisliAsync[F[_]: Async, R]: Async[Kleisli[F, R, ?]] =
    new KleisliAsync[F, R] { def F = Async[F] }

  implicit def scalazEitherTAsync[F[_]: Async, L]: Async[EitherT[F, L, ?]] =
    new EitherTAsync[F, L] { def F = Async[F] }

  implicit def scalazStateTAsync[F[_]: Async, S]: Async[StateT[F, S, ?]] =
    new StateTAsync[F, S] { def F = Async[F] }

  protected[this] trait OptionTAsync[F[_]] extends OptionTSync[F] with Async[OptionT[F, ?]] {
    protected implicit def F: Async[F]

    def asyncF[A](k: (Either[Throwable, A] => Unit) => OptionT[F, Unit]): OptionT[F, A] =
      OptionT.optionTMonadTrans.liftM(F.asyncF((cb: Either[Throwable, A] => Unit) => F.as(k(cb).run, ())))

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): OptionT[F, A] =
      OptionT.optionTMonadTrans.liftM(F.async(k))
  }

  protected[this] trait KleisliAsync[F[_], R] extends KleisliSync[F, R] with Async[Kleisli[F, R, ?]] {
    protected implicit def F: Async[F]

    override def asyncF[A](k: (Either[Throwable, A] => Unit) => Kleisli[F, R, Unit]): Kleisli[F, R, A] =
      Kleisli(a => F.asyncF(cb => k(cb).run(a)))

    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): Kleisli[F, R, A] =
      Kleisli(_ => F.async(k))
  }

  protected[this] trait EitherTAsync[F[_], L] extends EitherTSync[F, L] with Async[EitherT[F, L, ?]] {
    protected implicit def F: Async[F]

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): EitherT[F, L, A] =
      EitherT.rightT(F.async(k))

    def asyncF[A](k: (Either[Throwable, A] => Unit) => EitherT[F, L, Unit]): EitherT[F, L, A] =
      EitherT.rightT(F.asyncF(cb => F.as(k(cb).run, ())))
  }

  protected[this] trait StateTAsync[F[_], S] extends StateTSync[F, S] with Async[StateT[F, S, ?]] {
    protected implicit def F: Async[F]

    override def asyncF[A](k: (Either[Throwable, A] => Unit) => StateT[F, S, Unit]): StateT[F, S, A] =
      StateT(s => F.asyncF[A](cb => k(cb).eval(s)).map(a => (s, a)))

    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): StateT[F, S, A] =
      StateT.liftM(F.async(k))
  }
}

trait MTLEffect extends MTLAsync {

  implicit def scalazEitherTEffect[F[_]: Effect]: Effect[EitherT[F, Throwable, ?]] =
    new EitherTEffect[F] { def F = Effect[F] }

  protected[this] trait EitherTEffect[F[_]] extends EitherTAsync[F, Throwable] with Effect[EitherT[F, Throwable, ?]] {
    protected implicit def F: Effect[F]

    def runAsync[A](fa: EitherT[F, Throwable, A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
      F.runAsync(fa.run)(cb.compose(_.right.flatMap(x => x.asCats)))

    override def toIO[A](fa: EitherT[F, Throwable, A]): IO[A] =
      F.toIO(F.rethrow(fa.run.map(_.asCats)))
  }
}

trait MTLConcurrent extends MTLAsync {

  implicit def scalazOptionTConcurrent[F[_]: Concurrent]: Concurrent[OptionT[F, ?]] =
    new OptionTConcurrent[F] { def F = Concurrent[F] }

  implicit def scalazKleisliConcurrent[F[_]: Concurrent, R]: Concurrent[Kleisli[F, R, ?]] =
    new KleisliConcurrent[F, R] { def F = Concurrent[F] }

  protected[this] trait OptionTConcurrent[F[_]] extends OptionTAsync[F] with Concurrent[OptionT[F, ?]] {
    protected implicit def F: Concurrent[F]

    override def cancelable[A](
        k: (Either[Throwable, A] => Unit) => CancelToken[OptionT[F, ?]])
        : OptionT[F, A] =
      OptionT.optionTMonadTrans.liftM(F.cancelable(k.andThen(_.run.void)))

    override def start[A](fa: OptionT[F, A]) =
      OptionT.optionTMonadTrans.liftM(F.start(fa.run).map(fiberT))

    override def racePair[A, B](
        fa: OptionT[F, A],
        fb: OptionT[F, B])
        : OptionT[F, Either[(A, Fiber[OptionT[F, ?], B]), (Fiber[OptionT[F, ?], A], B)]] = {
      OptionT(F.racePair(fa.run, fb.run) flatMap {
        case Left((None, fiberB)) =>
          fiberB.cancel.map(_ => None)

        case Left((Some(r), fiberB)) =>
          F.pure(Some(Left((r, fiberT[B](fiberB)))))

        case Right((fiberA, None)) =>
          fiberA.cancel.map(_ => None)

        case Right((fiberA, Some(r))) =>
          F.pure(Some(Right((fiberT[A](fiberA), r))))
      })
    }

    protected def fiberT[A](fiber: Fiber[F, Option[A]]): Fiber[OptionT[F, ?], A] =
      Fiber(OptionT(fiber.join), OptionT.optionTMonadTrans.liftM(fiber.cancel))
  }

  protected[this] trait KleisliConcurrent[F[_], R] extends KleisliAsync[F, R] with Concurrent[Kleisli[F, R, ?]] {
    protected implicit def F: Concurrent[F]

    override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[Kleisli[F, R, ?]]): Kleisli[F, R, A] =
      Kleisli(r => F.cancelable(k.andThen(_.run(r))))

    override def start[A](fa: Kleisli[F, R, A]): Kleisli[F, R, Fiber[Kleisli[F, R, ?], A]] =
      Kleisli(r => F.start(fa.run(r)).map(fiberT))

    override def racePair[A, B](fa: Kleisli[F, R, A], fb: Kleisli[F, R, B]) =
      Kleisli { r =>
        F.racePair(fa.run(r), fb.run(r)) map {
          case Left((a, fiber)) => Left((a, fiberT[B](fiber)))
          case Right((fiber, b)) => Right((fiberT[A](fiber), b))
        }
      }

    protected def fiberT[A](fiber: Fiber[F, A]): Fiber[Kleisli[F, R, ?], A] =
      Fiber(Kleisli(_ => fiber.join), Kleisli(_ => fiber.cancel))
  }

  protected[this] trait EitherTConcurrent[F[_], L] extends EitherTAsync[F, L] with Concurrent[EitherT[F, L, ?]] {
    protected implicit def F: Concurrent[F]

    override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[EitherT[F, L, ?]]): EitherT[F, L, A] =
      EitherT.rightT(F.cancelable(k.andThen(_.run.void)))

    override def start[A](fa: EitherT[F, L, A]) =
      EitherT.rightT(F.start(fa.run).map(fiberT))

    override def racePair[A, B](
        fa: EitherT[F, L, A],
        fb: EitherT[F, L, B])
        : EitherT[F, L, Either[(A, Fiber[EitherT[F, L, ?], B]), (Fiber[EitherT[F, L, ?], A], B)]] = {

      EitherT.eitherT(F.racePair(fa.run, fb.run) flatMap {
        case Left((\/-(r), fiberB)) =>
          F.pure(\/-(Left((r, fiberT[B](fiberB)))))

          case Left((-\/(l), fiberB)) =>
            fiberB.cancel.as(-\/(l))

        case Right((fiberA, \/-(r))) =>
          F.pure(\/-(Right((fiberT[A](fiberA), r))))

        case Right((fiberA, -\/(l))) =>
          fiberA.cancel.as(-\/(l))
      })
    }

    protected def fiberT[A](fiber: Fiber[F, L \/ A]): Fiber[EitherT[F, L, ?], A] =
      Fiber(EitherT.eitherT(fiber.join), EitherT.rightT(fiber.cancel))
  }
}

trait MTLConcurrentEffect extends MTLEffect with MTLConcurrent {

  implicit def scalazEitherTConcurrentEffect[F[_]: ConcurrentEffect]: ConcurrentEffect[EitherT[F, Throwable, ?]] =
    new EitherTConcurrentEffect[F] { def F = ConcurrentEffect[F] }

  protected[this] trait EitherTConcurrentEffect[F[_]]
      extends EitherTEffect[F]
      with EitherTConcurrent[F, Throwable]
      with ConcurrentEffect[EitherT[F, Throwable, ?]] {

    protected implicit def F: ConcurrentEffect[F]

    override def runCancelable[A](
        fa: EitherT[F, Throwable, A])(
        cb: Either[Throwable, A] => IO[Unit])
        : SyncIO[CancelToken[EitherT[F, Throwable, ?]]] =
      F.runCancelable(fa.run)(cb.compose(_.right.flatMap(x => x.asCats))).map(EitherT.rightT(_))
  }
}

trait MTLContextShift extends MonadConversions {

  implicit def scalazOptionTContextShift[F[_]: Monad](
      implicit cs: ContextShift[F])
      : ContextShift[OptionT[F, ?]] = {
    new ContextShift[OptionT[F, ?]] {
      def shift: OptionT[F, Unit] =
        OptionT.optionTMonadTrans.liftM(cs.shift)

      def evalOn[A](ec: ExecutionContext)(fa: OptionT[F, A]): OptionT[F, A] =
        OptionT(cs.evalOn(ec)(fa.run))
    }
  }

  implicit def scalazKleisliContextShift[F[_], R](
      implicit cs: ContextShift[F])
      : ContextShift[Kleisli[F, R, ?]] = {
    new ContextShift[Kleisli[F, R, ?]] {
      def shift: Kleisli[F, R, Unit] =
        Kleisli(_ => cs.shift)

      def evalOn[A](ec: ExecutionContext)(fa: Kleisli[F, R, A]): Kleisli[F, R, A] =
        Kleisli(a => cs.evalOn(ec)(fa.run(a)))
    }
  }

  implicit def scalazEitherTContextShift[F[_]: Functor, L](
      implicit cs: ContextShift[F])
      : ContextShift[EitherT[F, L, ?]] = {
    new ContextShift[EitherT[F, L, ?]] {
      def shift: EitherT[F, L, Unit] =
        EitherT.rightT(cs.shift)

      def evalOn[A](ec: ExecutionContext)(fa: EitherT[F, L, A]): EitherT[F, L, A] =
        EitherT.eitherT(cs.evalOn(ec)(fa.run))
    }
  }

  implicit def scalazStateTContextShift[F[_]: Monad, S](
      implicit cs: ContextShift[F])
      : ContextShift[StateT[F, S, ?]] = {

    new ContextShift[StateT[F, S, ?]] {
      def shift: StateT[F, S, Unit] =
        StateT.liftM(cs.shift)

      def evalOn[A](ec: ExecutionContext)(fa: StateT[F, S, A]): StateT[F, S, A] =
        StateT(s => cs.evalOn(ec)(fa.run(s)))
    }
  }
}
