/*
 *  ____    ____    _____    ____    ___     ____
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.bytecode

import scalaz.Scalaz._

trait Instructions {
  type Lib <: Library
  val library: Lib

  import library._

  sealed trait Instruction

  // namespace
  object instructions {
    sealed trait DataInstr extends Instruction

    sealed trait JoinInstr extends Instruction

    case class Map1(op: UnaryOperation)       extends Instruction
    case class Map2Match(op: BinaryOperation) extends Instruction with JoinInstr
    case class Map2Cross(op: BinaryOperation) extends Instruction with JoinInstr

    case class Reduce(red: BuiltInReduction) extends Instruction
    case class Morph1(m1: BuiltInMorphism1)  extends Instruction
    case class Morph2(m2: BuiltInMorphism2)  extends Instruction

    case object Observe extends Instruction with JoinInstr

    case object Assert extends Instruction with JoinInstr

    case object IUnion        extends Instruction with JoinInstr
    case object IIntersect    extends Instruction with JoinInstr
    case object SetDifference extends Instruction with JoinInstr

    case class Group(id: Int)             extends Instruction
    case class MergeBuckets(and: Boolean) extends Instruction
    case class KeyPart(id: Int)           extends Instruction
    case object Extra extends Instruction

    case object Split extends Instruction
    case object Merge extends Instruction

    case object FilterMatch extends Instruction with DataInstr
    case object FilterCross extends Instruction with DataInstr

    case object Dup  extends Instruction
    case object Drop extends Instruction
    case class Swap(depth: Int) extends Instruction with DataInstr

    case class Line(line: Int, col: Int, text: String) extends Instruction with DataInstr {
      override def toString = "<%d:%d>".format(line, col)
    }

    case object AbsoluteLoad extends Instruction
    case object RelativeLoad extends Instruction
    case object Distinct     extends Instruction

    sealed trait RootInstr extends Instruction

    case class PushString(str: String) extends Instruction with DataInstr with RootInstr
    case class PushNum(num: String)    extends Instruction with DataInstr with RootInstr
    case object PushTrue   extends Instruction with RootInstr
    case object PushFalse  extends Instruction with RootInstr
    case object PushNull   extends Instruction with RootInstr
    case object PushObject extends Instruction with RootInstr
    case object PushArray  extends Instruction with RootInstr

    case object PushUndefined extends Instruction
    case class PushGroup(id: Int) extends Instruction
    case class PushKey(id: Int)   extends Instruction

    object Map2 {
      def unapply(instr: JoinInstr): Option[BinaryOperationType] = instr match {
        case Map2Match(op) => Some(op.tpe)
        case Map2Cross(op) => Some(op.tpe)
        case _             => None
      }
    }

    sealed trait UnaryOperation {
      val tpe: UnaryOperationType
    }

    trait NumericUnaryOperation extends UnaryOperation {
      val tpe = UnaryOperationType(JNumberT, JNumberT)
    }

    trait BooleanUnaryOperation extends UnaryOperation {
      val tpe = UnaryOperationType(JBooleanT, JBooleanT)
    }

    trait UnfixedUnaryOperation extends UnaryOperation {
      val tpe = UnaryOperationType(JType.JUniverseT, JType.JUniverseT)
    }

    sealed trait BinaryOperation {
      val tpe: BinaryOperationType
    }

    trait NumericBinaryOperation extends BinaryOperation {
      val tpe = BinaryOperationType(JNumberT, JNumberT, JNumberT)
    }

    trait NumericComparisonOperation extends BinaryOperation {
      val dateAndNum = JUnionT(JNumberT, JDateT)
      val tpe        = BinaryOperationType(dateAndNum, dateAndNum, JBooleanT)
    }

    trait BooleanBinaryOperation extends BinaryOperation {
      val tpe = BinaryOperationType(JBooleanT, JBooleanT, JBooleanT)
    }

    trait EqualityOperation extends BinaryOperation {
      val tpe = BinaryOperationType(JType.JUniverseT, JType.JUniverseT, JBooleanT)
    }

    trait UnfixedBinaryOperation extends BinaryOperation {
      val tpe = BinaryOperationType(JType.JUniverseT, JType.JUniverseT, JType.JUniverseT)
    }

    case class BuiltInFunction1Op(op: Op1) extends UnaryOperation {
      val tpe = op.tpe
    }

    case class BuiltInFunction2Op(op: Op2) extends BinaryOperation {
      val tpe = op.tpe
    }

    case class BuiltInMorphism1(mor: Morphism1) extends UnaryOperation {
      val tpe = mor.tpe
    }

    case class BuiltInMorphism2(mor: Morphism2) extends BinaryOperation {
      val tpe = mor.tpe
    }

    case class BuiltInReduction(red: Reduction) extends UnaryOperation {
      val tpe = red.tpe
    }

    case object Add extends NumericBinaryOperation
    case object Sub extends NumericBinaryOperation
    case object Mul extends NumericBinaryOperation
    case object Div extends NumericBinaryOperation
    case object Mod extends NumericBinaryOperation
    case object Pow extends NumericBinaryOperation

    case object Lt   extends NumericComparisonOperation
    case object LtEq extends NumericComparisonOperation
    case object Gt   extends NumericComparisonOperation
    case object GtEq extends NumericComparisonOperation

    case object Eq    extends EqualityOperation
    case object NotEq extends EqualityOperation

    case object Or  extends BooleanBinaryOperation
    case object And extends BooleanBinaryOperation

    case object New  extends UnfixedUnaryOperation
    case object Comp extends BooleanUnaryOperation
    case object Neg  extends NumericUnaryOperation

    case object WrapObject extends BinaryOperation {
      val tpe = BinaryOperationType(JTextT, JType.JUniverseT, JObjectUnfixedT)
    }
    case object WrapArray extends UnaryOperation {
      val tpe = UnaryOperationType(JType.JUniverseT, JArrayUnfixedT)
    }

    case object JoinObject extends BinaryOperation {
      val tpe = BinaryOperationType(JObjectUnfixedT, JObjectUnfixedT, JObjectUnfixedT)
    }
    case object JoinArray extends BinaryOperation {
      val tpe = BinaryOperationType(JArrayUnfixedT, JArrayUnfixedT, JArrayUnfixedT)
    }

    case object ArraySwap extends BinaryOperation {
      val tpe = BinaryOperationType(JArrayUnfixedT, JNumberT, JArrayUnfixedT)
    }

    case object DerefObject extends BinaryOperation {
      val tpe = BinaryOperationType(JObjectUnfixedT, JTextT, JType.JUniverseT)
    }
    case object DerefMetadata extends UnfixedBinaryOperation
    case object DerefArray extends BinaryOperation {
      val tpe = BinaryOperationType(JArrayUnfixedT, JNumberT, JType.JUniverseT)
    }

    case object Range
  }
}