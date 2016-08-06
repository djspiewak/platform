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
package com.precog.common
package security

import accounts.AccountId
import blueeyes._
import service.v1
import org.slf4s.Logging
import scalaz._, Scalaz._

trait APIKeyFinder[M[+ _]] extends AccessControl[M] with Logging { self =>
  def findAPIKey(apiKey: APIKey, rootKey: Option[APIKey]): M[Option[v1.APIKeyDetails]]

  def findAllAPIKeys(fromRoot: APIKey): M[Set[v1.APIKeyDetails]]

  def createAPIKey(accountId: AccountId, keyName: Option[String] = None, keyDesc: Option[String] = None): M[v1.APIKeyDetails]

  def addGrant(accountKey: APIKey, grantId: GrantId): M[Boolean]

  def withM[N[+ _]](implicit t: M ~> N) = new APIKeyFinder[N] {
    def findAPIKey(apiKey: APIKey, rootKey: Option[APIKey]) =
      t(self.findAPIKey(apiKey, rootKey))

    def findAllAPIKeys(fromRoot: APIKey) =
      t(self.findAllAPIKeys(fromRoot))

    def createAPIKey(accountId: AccountId, keyName: Option[String] = None, keyDesc: Option[String] = None) =
      t(self.createAPIKey(accountId, keyName, keyDesc))

    def addGrant(accountKey: APIKey, grantId: GrantId) =
      t(self.addGrant(accountKey, grantId))

    def hasCapability(apiKey: APIKey, perms: Set[Permission], at: Option[DateTime]) =
      t(self.hasCapability(apiKey, perms, at))
  }
}

class DirectAPIKeyFinder[M[+ _]](underlying: APIKeyManager[M])(implicit val M: Monad[M]) extends APIKeyFinder[M] with Logging {
  val grantDetails: Grant => v1.GrantDetails = {
    case Grant(gid, gname, gdesc, _, _, perms, createdAt, exp) => v1.GrantDetails(gid, gname, gdesc, perms, createdAt, exp)
  }

  def recordDetails(rootKey: Option[APIKey]): PartialFunction[APIKeyRecord, M[v1.APIKeyDetails]] = {
    case APIKeyRecord(apiKey, name, description, issuer, grantIds, false) =>
      underlying.findAPIKeyAncestry(apiKey).flatMap { ancestors =>
        val ancestorKeys = ancestors.drop(1).map(_.apiKey) // The first element of ancestors is the key itself, so we drop it
        grantIds.map(underlying.findGrant).toList.sequence map { grants =>
          val divulgedIssuers = rootKey.map { rk =>
            ancestorKeys.reverse.dropWhile(_ != rk).reverse
          }.getOrElse(Nil)
          log.debug("Divulging issuers %s for key %s based on root key %s and ancestors %s".format(divulgedIssuers, apiKey, rootKey, ancestorKeys))
          v1.APIKeyDetails(apiKey, name, description, grants.flatten.map(grantDetails)(collection.breakOut), divulgedIssuers)
        }
      }
  }

  val recordDetails: PartialFunction[APIKeyRecord, M[v1.APIKeyDetails]] = recordDetails(None)

  def hasCapability(apiKey: APIKey, perms: Set[Permission], at: Option[DateTime]): M[Boolean] = {
    underlying.hasCapability(apiKey, perms, at)
  }

  def findAPIKey(apiKey: APIKey, rootKey: Option[APIKey]) = {
    underlying.findAPIKey(apiKey) flatMap {
      _.collect(recordDetails(rootKey)).sequence
    }
  }

  def findAllAPIKeys(fromRoot: APIKey): M[Set[v1.APIKeyDetails]] = {
    underlying.findAPIKey(fromRoot) flatMap {
      case Some(record) =>
        underlying.findAPIKeyChildren(record.apiKey) flatMap { recs =>
          (recs collect recordDetails).toList.sequence.map(_.toSet)
        }

      case None =>
        M.point(Set())
    }
  }

  def createAPIKey(accountId: AccountId, keyName: Option[String] = None, keyDesc: Option[String] = None): M[v1.APIKeyDetails] = {
    underlying.newStandardAPIKeyRecord(accountId, keyName, keyDesc) flatMap recordDetails
  }

  def addGrant(accountKey: APIKey, grantId: GrantId): M[Boolean] = {
    underlying.addGrants(accountKey, Set(grantId)) map { _.isDefined }
  }
}