package com.twitter.algebird

import org.scalatest.PropSpec
import org.scalatest.prop.PropertyChecks
import org.scalatest.prop.Checkers.check
import org.scalacheck.{ Gen, Arbitrary }
import Arbitrary.arbitrary

class ExpHistLaws extends PropSpec with PropertyChecks {
  import ExpHistGenerators._
  import ExpHist.{ Bucket, Config }

  property("Increment example from DGIM paper") {
    // Returns a vector of bucket sizes from largest to smallest.
    def bSizes(e: ExpHist): Vector[Long] = e.buckets.reverse.map(_.size)

    // epsilon of 0.5 gives us window sizes of 1 or 2.
    val e = ExpHist.empty(Config(0.5, 100))

    val plus76 = e.add(76, 0)
    val inc = plus76.inc(0)
    val twoMore = inc.add(2, 0)

    assert(bSizes(plus76) == Vector(32, 16, 8, 8, 4, 4, 2, 1, 1))
    assert(bSizes(inc) == Vector(32, 16, 8, 8, 4, 4, 2, 2, 1))
    assert(bSizes(twoMore) == Vector(32, 16, 16, 8, 4, 2, 1))
  }

  /**
   * An "exponential histogram" tracks the count of a sliding window
   * with a fixed maximum relative error. The core guarantees are:
   *
   * - The actual sum will always be within the tracked bounds
   * - The EH's guess is always within epsilon the actual.
   * - The relative error of the count is at most epsilon
   * - the relative error is always between 0 and 0.5.
   */
  def checkCoreProperties(eh: ExpHist, actualSum: Long) {
    assert(eh.lowerBoundSum <= actualSum)
    assert(eh.upperBoundSum >= actualSum)

    val maxError = actualSum * eh.relativeError
    assert(eh.guess <= actualSum + maxError)
    assert(eh.guess >= actualSum - maxError)

    assert(eh.relativeError <= eh.conf.epsilon)

    assert(eh.relativeError <= 0.5)
    assert(eh.relativeError >= 0)
  }

  /**
   * Returns the ACTUAL sum of the supplied vector of buckets,
   * filtering out any bucket with a timestamp <= exclusiveCutoff.
   */
  def actualBucketSum(buckets: Vector[Bucket], exclusiveCutoff: Long): Long =
    buckets.collect {
      case Bucket(count, ts) if ts > exclusiveCutoff => count
    }.sum

  // addAll and add are NOT guaranteed to return the same exponential
  // histogram, but either method of inserting buckets needs to return
  // an EH that satisfies the core properties.
  property("ExpHist.addAll satisfies core properties") {
    forAll { (v: NonEmptyVector[Bucket], conf: Config) =>
      val buckets = v.items
      val mostRecentTs = buckets.maxBy(_.timestamp).timestamp
      val cutoff = conf.expiration(mostRecentTs)

      val fullViaAddAll = ExpHist.empty(conf).addAll(buckets)
      val actualSum = actualBucketSum(buckets, cutoff)
      checkCoreProperties(fullViaAddAll, actualSum)
    }
  }

  property("ExpHist.add satisfies core properties") {
    forAll { (items: NonEmptyVector[Bucket], conf: Config) =>
      val buckets = items.sorted
      val mostRecentTs = buckets.last.timestamp
      val cutoff = conf.expiration(mostRecentTs)

      val fullViaAdd = buckets.foldLeft(ExpHist.empty(conf)) {
        case (e, Bucket(c, t)) => e.add(c, t)
      }

      val actualSum = actualBucketSum(buckets, cutoff)
      checkCoreProperties(fullViaAdd, actualSum)
    }
  }

  def isPowerOfTwo(i: Long): Boolean = (i & -i) == i

  property("verify isPowerOfTwo") {
    forAll { i: PosNum[Int] =>
      val power = math.pow(2, i.value % 64).toLong
      assert(isPowerOfTwo(power))
    }
  }

  property("bucket sizes are all powers of two") {
    forAll { e: ExpHist =>
      assert(e.buckets.forall { b => isPowerOfTwo(b.size) })
    }
  }

  property("Total tracked by e is the sum of all bucket sizes") {
    forAll { e: ExpHist =>
      assert(e.buckets.map(_.size).sum == e.total)
    }
  }

  property("ExpHist bucket sizes are the l-canonical rep of the tracked total") {
    forAll { e: ExpHist =>
      assert(e.buckets.map(_.size) == Canonical.bucketsFromLong(e.total, e.conf.l))
    }
  }

  property("adding i results in upperBoundSum == i") {
    // since every bucket has the same timestamp, if nothing expires
    // then the upper bound is equal to the actual total.
    forAll { (conf: Config, bucket: Bucket) =>
      assert(ExpHist.empty(conf)
        .add(bucket.size, bucket.timestamp)
        .upperBoundSum == bucket.size)
    }
  }

  property("ExpHist.empty.add(i) and ExpHist.from(i) are identical") {
    forAll { (conf: Config, bucket: Bucket) =>
      val byAdd = ExpHist.empty(conf).add(bucket.size, bucket.timestamp)
      val byFrom = ExpHist.from(bucket.size, bucket.timestamp, conf)
      assert(byAdd == byFrom)
    }
  }

  property("step should be idempotent") {
    forAll { (expHist: ExpHist, bucket: Bucket) =>
      val t = bucket.timestamp
      val stepped = expHist.step(t)
      assert(stepped == stepped.step(t))

      val added = expHist.add(bucket.size, t)
      val addThenStep = added.step(t)
      val stepThenAdd = stepped.add(bucket.size, t)

      assert(added == addThenStep)
      assert(added == stepThenAdd)
      assert(addThenStep == stepThenAdd)
    }
  }

  property("step(t) == add(0, t)") {
    forAll { (expHist: ExpHist, ts: PosNum[Long]) =>
      assert(expHist.step(ts.value) == expHist.add(0, ts.value))
    }
  }

  property("add(i) and inc i times should generate the same EH") {
    forAll { (bucket: Bucket, conf: Config) =>
      val e = ExpHist.empty(conf)

      val incs = (0L until bucket.size).foldLeft(e) {
        case (acc, _) => acc.inc(bucket.timestamp)
      }.step(bucket.timestamp)
      val adds = e.add(bucket.size, bucket.timestamp)
      assert(incs == adds)
    }
  }

  property("empty EH returns 0 for all metrics") {
    forAll { (conf: Config) =>
      val e = ExpHist.empty(conf)
      assert(e.guess == 0)
      assert(e.total == 0)
      assert(e.relativeError == 0)
      assert(e.upperBoundSum == 0)
      assert(e.lowerBoundSum == 0)
    }
  }

  property("dropExpired works properly") {
    forAll { (v: NonEmptyVector[Bucket], window: PosNum[Long]) =>
      val buckets = v.sorted.reverse
      val cutoff = buckets.head.timestamp - window.value

      val (droppedSum, remaining) = ExpHist.dropExpired(buckets, cutoff)

      assert(droppedSum == buckets.filter(_.timestamp <= cutoff).map(_.size).sum)
      assert(remaining == buckets.filter(_.timestamp > cutoff))
    }
  }

  property("rebucketing into bucket sizes from a canonical rep works") {
    forAll { (v: NonEmptyVector[Bucket], k: PosNum[Short]) =>
      val buckets = v.sorted
      val total = buckets.map(_.size).sum
      val desired = Canonical.bucketsFromLong(total, k.value)
      val rebucketed = ExpHist.rebucket(buckets, desired)

      // rebucketing doesn't change the sum of bucket sizes.
      assert(rebucketed.map(_.size).sum == total)

      // rebucketing works - the final sequence of sizes matches the
      // desired sequence.
      assert(rebucketed.map(_.size) == desired)

      // all bucket sizes are now powers of two.
      assert(rebucketed.forall { b => isPowerOfTwo(b.size) })
    }
  }
}

object ExpHistGenerators {
  import ExpHist.{ Bucket, Config }

  implicit val arb: Arbitrary[Bucket] =
    Arbitrary(for {
      count <- Gen.posNum[Long]
      timestamp <- Gen.posNum[Long]
    } yield Bucket(count - 1L, timestamp))

  implicit val conf: Arbitrary[Config] =
    Arbitrary(for {
      k <- Gen.posNum[Short]
      windowSize <- Gen.posNum[Long]
    } yield Config(1 / k.toDouble, windowSize))

  implicit val expHist: Arbitrary[ExpHist] =
    Arbitrary(for {
      buckets <- arbitrary[Vector[Bucket]]
      conf <- arbitrary[Config]
    } yield ExpHist.empty(conf).addAll(buckets))
}

class CanonicalLaws extends PropSpec with PropertyChecks {
  import Canonical._

  property("l-canonical representation is all l or l+1s except for last") {
    forAll { (i: PosNum[Long], l: PosNum[Short]) =>
      val rep = fromLong(i.value, l.value)

      // all values but the last are l or l + 1
      assert(rep.init.forall(v => v == l.value || v == l.value + 1))

      assert(rep.last <= l.value + 1)
    }
  }

  property("canonical representation round-trips") {
    forAll { (i: PosNum[Long], l: PosNum[Short]) =>
      assert(toLong(fromLong(i.value, l.value)) == i.value)
    }
  }

  property("fromLong(i, k).sum == # of buckets required to encode i") {
    forAll { (i: PosNum[Long], k: PosNum[Short]) =>
      val rep = fromLong(i.value, k.value)
      val numBuckets = toBuckets(rep).size

      assert(rep.sum == numBuckets)
    }
  }

  property("bucketsFromLong(i, k).sum generates buckets directly") {
    forAll { (i: PosNum[Long], k: PosNum[Short]) =>
      val rep = fromLong(i.value, k.value)
      assert(bucketsFromLong(i.value, k.value) == toBuckets(rep))
    }
  }

  property("all i except last have either k/2, k/2 + 1 buckets") {
    forAll { (i: PosNum[Long], k: PosNum[Short]) =>
      val lower = k.value
      val upper = lower + 1
      assert(
        fromLong(i.value, k.value).init.forall { numBuckets =>
          lower <= numBuckets && numBuckets <= upper
        })
    }
  }
}
