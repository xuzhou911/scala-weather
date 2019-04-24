/*
 * Copyright (c) 2015-2019 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.weather
package providers.openweather

import scala.concurrent.duration._

import cats.{Eval, Id, Monad}
import cats.data.EitherT
import cats.effect.Sync
import cats.syntax.either._
import com.snowplowanalytics.lrumap.CreateLruMap

import Cache.CacheKey
import errors.{InvalidConfigurationError, WeatherError}
import responses.History

trait CreateOWM[F[_]] {

  /**
   * Create an `OWMClient`
   * @param apiHost URL to the OpenWeatherMap API endpoints
   * @param apiKey API key from OpenWeatherMap
   * @return an OWMClient
   */
  def create(apiHost: String, apiKey: String, timeout: FiniteDuration): OWMClient[F]

  /**
   * Create an `OwmCacheClient` capable of caching results
   * @param apiHost URL to the OpenWeatherMap API endpoints
   * @param apiKey API key from OpenWeatherMap
   * @param timeout time after which active request will be considered failed
   * @param cacheSize amount of history requests storing in cache
   * it's better to store whole OWM packet (5000/50000/150000) plus some space for errors (~1%)
   * @param geoPrecision nth part of 1 to which latitude and longitude will be rounded
   * stored in cache. e.g. coordinate 45.678 will be rounded to values 46.0, 45.5, 45.7, 45.78 by
   * geoPrecision 1,2,10,100 respectively. geoPrecision 1 will give ~60km infelicity in the worst
   * case; 2 ~30km etc
   * @return either an InvalidConfigurationError or a OWMCacheClient
   */
  def create(
    apiHost: String,
    apiKey: String,
    timeout: FiniteDuration,
    cacheSize: Int,
    geoPrecision: Int
  ): F[Either[InvalidConfigurationError, OWMCacheClient[F]]]
}

object CreateOWM {
  def apply[F[_]](implicit ev: CreateOWM[F]): CreateOWM[F] = ev

  implicit def syncCreateOWM[F[_]: Sync: Transport]: CreateOWM[F] = new CreateOWM[F] {
    def create(apiHost: String, apiKey: String, timeout: FiniteDuration): OWMClient[F] =
      new OWMClient(apiHost, apiKey, timeout, ssl = true)
    def create(
      apiHost: String,
      apiKey: String,
      timeout: FiniteDuration,
      cacheSize: Int,
      geoPrecision: Int
    ): F[Either[InvalidConfigurationError, OWMCacheClient[F]]] =
      cacheClient[F](apiHost, apiKey, timeout, cacheSize, geoPrecision, ssl = true)
  }

  implicit def evalCreateOWM(implicit T: Transport[Eval]): CreateOWM[Eval] = new CreateOWM[Eval] {
    def create(apiHost: String, apiKey: String, timeout: FiniteDuration): OWMClient[Eval] =
      new OWMClient(apiHost, apiKey, timeout, ssl = true)
    def create(
      apiHost: String,
      apiKey: String,
      timeout: FiniteDuration,
      cacheSize: Int,
      geoPrecision: Int
    ): Eval[Either[InvalidConfigurationError, OWMCacheClient[Eval]]] =
      cacheClient[Eval](apiHost, apiKey, timeout, cacheSize, geoPrecision, ssl = true)
  }

  implicit def idCreateOWM(implicit T: Transport[Id]): CreateOWM[Id] = new CreateOWM[Id] {
    def create(apiHost: String, apiKey: String, timeout: FiniteDuration): OWMClient[Id] =
      new OWMClient(apiHost, apiKey, timeout, ssl = true)
    def create(
      apiHost: String,
      apiKey: String,
      timeout: FiniteDuration,
      cacheSize: Int,
      geoPrecision: Int
    ): Id[Either[InvalidConfigurationError, OWMCacheClient[Id]]] =
      cacheClient[Id](apiHost, apiKey, timeout, cacheSize, geoPrecision, ssl = true)
  }

  private[openweather] def cacheClient[F[_]: Monad](
    apiHost: String,
    apiKey: String,
    timeout: FiniteDuration,
    cacheSize: Int,
    geoPrecision: Int,
    ssl: Boolean
  )(
    implicit CLM: CreateLruMap[F, CacheKey, Either[WeatherError, History]],
    T: Transport[F]
  ): F[Either[InvalidConfigurationError, OWMCacheClient[F]]] =
    (for {
      _ <- EitherT.fromEither[F] {
        ().asRight
          .filterOrElse(
            _ => geoPrecision > 0,
            InvalidConfigurationError("geoPrecision must be greater than 0")
          )
          .filterOrElse(
            _ => cacheSize > 0,
            InvalidConfigurationError("cacheSize must be greater than 0")
          )
      }
      cache <- EitherT.right[InvalidConfigurationError](Cache.init(cacheSize, geoPrecision))
      client = new OWMCacheClient[F](cache, apiHost, apiKey, timeout, ssl)
    } yield client).value
}
