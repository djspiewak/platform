package ygg.table

import ygg.cf
import trans._

object F1Expr {
  def negate         = cf.math.Negate
  def coerceToDouble = cf.CoerceToDouble
  def moduloTwo      = cf.math.Mod applyr CLong(2)
  def equalsZero     = cf.std.Eq applyr CLong(0)
  def isEven         = moduloTwo andThen equalsZero
}
object Fn {
  def source                    = Leaf(Source)
  def valueIsEven(name: String) = root select name map1 F1Expr.isEven
  def constantTrue              = Filter(source, Equal(source, source))
}
trait CF1Like {
  def compose(f1: CF1): CF1
  def andThen(f1: CF1): CF1
}
trait CF2Like {
  def applyl(cv: CValue): CF1
  def applyr(cv: CValue): CF1
  def andThen(f1: CF1): CF2
}

sealed trait Definedness
case object AnyDefined extends Definedness
case object AllDefined extends Definedness