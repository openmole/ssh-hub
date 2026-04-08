package miniclust.pilot

/*
 * Copyright (C) 2026 Romain Reuillon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


object Configuration:
  import io.circe.*
  import io.circe.generic.semiauto.*

  given io.circe.derivation.Configuration = io.circe.derivation.Configuration.default.withDiscriminator("type").withKebabCaseConstructorNames.withKebabCaseMemberNames.withDefaults

  case class Server(name: String, host: String, login: String, proxy: Option[String], env: Seq[Map[String, String]] = Seq()) derives io.circe.derivation.ConfiguredCodec
  case class Script(name: String, run: String) derives io.circe.derivation.ConfiguredCodec

case class Configuration(server: Seq[Configuration.Server], script: Seq[Configuration.Script], test: Seq[Configuration.Script] = Seq()) derives io.circe.derivation.ConfiguredCodec
