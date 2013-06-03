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
package com.precog.ingest
package service

import akka.dispatch.{ExecutionContext, Future}

import blueeyes.core.data.ByteChunk
import blueeyes.core.http.MimeType
import blueeyes.core.http.HttpHeaders._
import blueeyes.core.http.HttpRequest
import blueeyes.core.http.MimeTypes._
import blueeyes.json.{AsyncParser, AsyncParse, JParser}

import com.precog.common.Path
import com.precog.common.jobs.JobId
import com.precog.common.security.{APIKey, Authorities}

import java.io.File
import java.nio.ByteBuffer

import scala.annotation.tailrec

import scalaz._
import scalaz.syntax.std.boolean._

trait IngestProcessor {
  def processBatch(data: ByteChunk, parseDirectives: Set[ParseDirective], jobId: JobId, sync: Boolean): Future[BatchIngestResult]
  def processStream(data: ByteChunk, parseDirectives: Set[ParseDirective]): Future[StreamingIngestResult]
}

class IngestProcessorSelection(maxFields: Int, batchSize: Int, tmpdir: File, ingestStore: IngestStore)(implicit M: Monad[Future], executor: ExecutionContext){
  val JSON = application/json
  val JSON_STREAM = MimeType("application", "x-json-stream")
  val CSV = text/csv

  /** Chain of responsibility used to determine a IngestProcessor strategy */
  trait IngestProcessorSelector {
    def select(partialData: Array[Byte], parseDirectives: Set[ParseDirective]): Option[IngestProcessor]
  }

  class MimeIngestProcessorSelector(apiKey: APIKey, path: Path, authorities: Authorities) extends IngestProcessorSelector {
    def select(partialData: Array[Byte], parseDirectives: Set[ParseDirective]): Option[IngestProcessor] = {
      parseDirectives collectFirst {
        case MimeDirective(JSON) => new JSONIngestProcessor(apiKey, path, authorities, JsonValueStyle, maxFields, ingestStore)
        case MimeDirective(JSON_STREAM) => new JSONIngestProcessor(apiKey, path, authorities, JsonStreamStyle, maxFields, ingestStore)
        case MimeDirective(CSV) => new CSVIngestProcessor(apiKey, path, authorities, batchSize, ingestStore, tmpdir)
      }
    }
  }

  class JsonIngestProcessorSelector(apiKey: APIKey, path: Path, authorities: Authorities) extends IngestProcessorSelector {
    def select(partialData: Array[Byte], parseDirectives: Set[ParseDirective]): Option[IngestProcessor] = {
      val (AsyncParse(errors, values), parser) = AsyncParser(true).apply(Some(ByteBuffer.wrap(partialData)))
      if (errors.isEmpty && !values.isEmpty) {
        parseDirectives collectFirst {
          case MimeDirective(JSON_STREAM) => new JSONIngestProcessor(apiKey, path, authorities, JsonStreamStyle, maxFields, ingestStore)
        } orElse {
          Some(new JSONIngestProcessor(apiKey, path, authorities, JsonValueStyle, maxFields, ingestStore))
        } 
      } else None
    }
  }

  def ingestSelectors(apiKey: APIKey, path: Path, authorities: Authorities): List[IngestProcessorSelector] = List(
    new MimeIngestProcessorSelector(apiKey, path, authorities),
    new JsonIngestProcessorSelector(apiKey, path, authorities)
  )

  def getParseDirectives(request: HttpRequest[_]): Set[ParseDirective] = {
    val mimeDirective =
      for {
        header <- request.headers.header[`Content-Type`]
        mimeType <- header.mimeTypes.headOption
      } yield MimeDirective(mimeType)

    val delimiter = request.parameters get 'delimiter map { CSVDelimiter(_) }
    val quote = request.parameters get 'quote map { CSVQuote(_) }
    val escape = request.parameters get 'escape map { CSVEscape(_) }

    mimeDirective.toSet ++ delimiter ++ quote ++ escape
  }

  @tailrec final def selectIngestProcessor(from: List[IngestProcessorSelector], partialData: Array[Byte], parseDirectives: Set[ParseDirective]): Option[IngestProcessor] = {
    from match {
      case hd :: tl =>
        hd.select(partialData, parseDirectives) match { // not using map so as to get tailrec
          case None => selectIngestProcessor(tl, partialData, parseDirectives)
          case some => some
        }

      case Nil => None
    }
  }
}
