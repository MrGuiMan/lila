package lila.pool

import scala.math.abs

import lila.common.WMMatching

object MatchMaking {

  case class Couple(p1: PoolMember, p2: PoolMember) {
    def members    = Vector(p1, p2)
    def userIds    = members.map(_.userId)
    def ratingDiff = p1 ratingDiff p2
  }

  def apply(members: Vector[PoolMember]): Vector[Couple] = members.partition(_.lame) match {
    case (lames, fairs) => naive(lames) ++ (wmMatching(fairs) | naive(fairs))
  }

  private def naive(members: Vector[PoolMember]): Vector[Couple] =
    members.sortBy(-_.rating) grouped 2 collect {
      case Vector(p1, p2) => Couple(p1, p2)
    } toVector

  private object wmMatching {

    // above that, no pairing is allowed
    // 1000 ~> 150
    // 1200 ~> 125
    // 1500 ~> 125
    // 2000 ~> 166
    // 2500 ~> 208
    // 3000 ~> 250
    private def ratingToMaxScore(rating: Int) =
      if (rating < 1000) 150
      else if (rating < 1800) 120
      else rating / 12

    // quality of a potential pairing. Lower is better.
    // None indicates a forbidden pairing
    private def pairScore(a: PoolMember, b: PoolMember): Option[Int] = {
      val score = a.ratingDiff(b) - {
        missBonus(a) atMost missBonus(b)
      } + {
        rangeMalus(a, b) + rangeMalus(b, a)
      } - {
        rangeBonus(a, b)
      } + {
        blockMalus(a, b) + blockMalus(b, a)
      } - {
        ragesitBonus(a, b)
      }
      val maxScore = ratingToMaxScore(a.rating atMost b.rating)
      if (score <= maxScore) Some(score) else None
    }

    // score bonus based on how many waves the member missed
    // when the user's sit counter is lower than -3, the maximum bonus becomes lower
    private def missBonus(p: PoolMember) =
      (p.misses * 12) atMost ((460 + (p.rageSitCounter atMost -3) * 20) atLeast 0)

    // big malus if players have conflicting rating ranges
    private def rangeMalus(a: PoolMember, b: PoolMember) =
      if (a.ratingRange.exists(!_.contains(b.rating))) 9000 else 0

    // bonus if both players have rating ranges, and they're compatible
    private def rangeBonus(a: PoolMember, b: PoolMember) =
      if (a.ratingRange.exists(_ contains b.rating) && b.ratingRange.exists(_ contains a.rating)) 200
      else 0

    // huge malus if players block each other
    private def blockMalus(a: PoolMember, b: PoolMember) =
      if (a.blocking.ids contains b.userId) 9000 else 0

    // bonus if the two players both have a good sit counter
    // bonus if the two players both have a bad sit counter
    // malus (so negative number as bonus) if neither of those are true, meaning that their sit counters are far away (e.g. 0 and -5)
    private def ragesitBonus(a: PoolMember, b: PoolMember) =
      if (a.rageSitCounter >= -2 && b.rageSitCounter >= -2) 30        // good players
      else if (a.rageSitCounter <= -10 && b.rageSitCounter <= -10) 80 // very bad players
      else if (a.rageSitCounter <= -5 && b.rageSitCounter <= -5) 30   // bad players
      else (abs(a.rageSitCounter - b.rageSitCounter) atMost 10) * -30 // match of good and bad player

    def apply(members: Vector[PoolMember]): Option[Vector[Couple]] = {
      WMMatching(members.toArray, pairScore).fold(
        err => {
          logger.error("WMMatching", err)
          none
        },
        pairs =>
          Some {
            pairs.view.map { case (a, b) => Couple(a, b) } to Vector
          }
      )
    }
  }
}
