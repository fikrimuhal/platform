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
package com.querio
package daze

import org.specs2.mutable._

object EvaluatorSpecs extends Specification with Evaluator {
  import Function._
  import IterV._
  
  import dag._
  import instructions._
  
  "bytecode evaluator" should {
    "evaluate simple two-value multiplication" in {
      val line = Line(0, "")
      val input = Join(line, Map2Cross(Mul), Root(line, PushNum("6")), Root(line, PushNum("7")))
      val result = consumeEval(input)
      
      result must haveSize(1)
      
      val result2 = result collect {
        case SDecimal(d) => d.toInt
      }
      
      result2 must contain(42)
    }
  }
  
  override object query extends StorageEngineQueryAPI {
    private var pathIds = Map[Path, Int]()
    private var currentId = 0
    
    def fullProjection[X](path: Path): DatasetEnum[X, SEvent, IO] =
      DatasetEnum(readJSON[X](path))
    
    private def readJSON[X](path: Path) = {
      val src = Source.fromInputStream(Class getResourceAsStream path.elements.mkString("/", "/", ".json"))
      val stream = Stream from 0 map scaleId(path) zip (src.getLines map parseJSON toStream) map tupled(wrapSEvent)
      Iteratee.enumPStream[X](stream)
    }
    
    private def scaleId(path: Path)(seed: Int): Long = {
      val scalar = synchronized {
        if (!(pathIds contains path)) {
          pathIds += (path -> currentId)
          currentId += 1
        }
        
        pathIds(path)
      }
      
      (scalar.toLong << 32) | seed
    }
    
    private def parseJSON(str: String): JValue =
      JsonParser parse str
    
    private def wrapSEvent(id: Long, value: JValue): SEvent =
      (Vector(id), wrapSValue(value))
    
    private def wrapSValue(value: JValue): SValue = new SValue {
      def fold[A](
          obj: Map[String, SValue] => A,
          arr: Vector[SValue] => A,
          str: String => A,
          bool: Boolean => A,
          long: Long => A,
          double: Double => A,
          num: BigDecimal => A,
          nul: => A): A = value match {
            
        case JObject(fields) => {
          val pairs = fields map {
            case JField(key, value) => (key, wrapSValue(value))
          }
          
          obj(Map(pairs: _*))
        }
        
        case JArray(values) => arr(Vector(values map wrapSValue: _*))
        
        case JString(s) => str(s)
        
        case JBoolean(b) => bool(b)
        
        case JNum(d) => num(d)
        
        case JNull => nul
        
        case JNothing => sys.error("Hit JNothing")
      }
    }
  }
  
  private def consumeEval(graph: DepGraph): Set[SEvent] =
    (consume >>== eval(graph).enum) run { err => sys.error("O NOES!!!") }
  
  // apparently, this doesn't *really* exist in Scalaz
  private def consume: IterV[A, Set[A]] = {
    def step(acc: Set[A])(in: Input[A]): IterV[A, Set[A]] = {
      in(el = { e => Cont(step(acc + e)) },
         empty = Cont(step(acc)),
         eof = Done(acc, EOF.apply))
    }
    
    Cont(step(Set()))
  }
}