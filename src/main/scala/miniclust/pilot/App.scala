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
 * You should have received a copy of the GNU  General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import io.circe.yaml
import layoutz.*
import better.files.*
import monocle.syntax.all.*
import io.circe.generic.auto.*
import miniclust.pilot.State.ServerState.TestStatus
import miniclust.pilot.State.{DisplayState, ScriptPage}

object State:
  object Server:

    enum ExecutionStatus:
      def isAvailable: Boolean =
        this match
          case _: Executed => true
          case _: Running => false
          case _: Error => true
          case Nop => true

      case Executed(script: Int, out: String, code: Int) extends ExecutionStatus
      case Running(script: Int, out: StringBuilder) extends ExecutionStatus
      case Error(script: Int, message: String) extends ExecutionStatus
      case Nop extends ExecutionStatus


  case class Server(name: String, host: String, login: String, proxy: Option[Server], environment: Seq[(String, String)])

  object ServerState:
    enum SSHStatus:
      case unknown, ok, failed

    enum TestStatus:
      case unknown, ok, failed

    enum Page:
      case server, script
      case execution(server: Int) extends Page

  case class DisplayState(pageSize: Int, selected: Int)
  case class ServerState(server: Server, status: ServerState.SSHStatus, testStatus: List[TestStatus], executionStatus: Server.ExecutionStatus = Server.ExecutionStatus.Nop)
  case class ServerPage(server: List[State.ServerState], display: DisplayState = State.DisplayState(40, 0))
  case class ScriptPage(script: List[Configuration.Script], display: DisplayState = State.DisplayState(40, 0))

  def retryOnError[T](n: Int)(f: => T, sleep: Int = 100): T =
    try f
    catch
      case e: Exception if n > 0 =>
        Thread.sleep(sleep)
        retryOnError(n - 1)(f)


  def execute(server: Server, script: String, retry: Int = 5, out: Option[StringBuilder] = None) =
    import scala.sys.process.*
    val proxies = Iterator.iterate(server.proxy)(_.flatMap(_.proxy)).takeWhile(_.isDefined).flatten.toList

    val jumpArgs =
      if proxies.nonEmpty
      then Seq("-J", proxies.map(p => s"${p.login}@${p.host}").mkString(","))
      else Seq()

    val sshCmd =
      Seq("ssh",
        "-o", "StrictHostKeyChecking=no",
        "-o", "BatchMode=yes") ++
        jumpArgs ++ Seq(
          s"${server.login}@${server.host}",
        "bash"
        )

    val fullScript =
      val variables = server.environment.map((k, v) => s"""$k="$v"""").mkString("\n")
      s"""$variables
         |$script""".stripMargin

    val (exitCode, output) =
      retryOnError(retry):
        val output = out.getOrElse(new StringBuilder)
        val logger = ProcessLogger(line => output.append(line + "\n"))

        val c =
          Process(sshCmd)
            .#<(new java.io.ByteArrayInputStream(fullScript.getBytes("UTF-8")))
            .!(logger)

        if c == 255 then throw new RuntimeException(s"SSH connection failed")

        (c, output.toString())

    (output, exitCode)

//  def toGridscaleSSHServer(server: State.Server): gridscale.ssh.SSHServer =
//    def toGridscaleAuth(auth: Configuration.Authentication) =
//      auth match
//        case a: Configuration.Authentication.Password => gridscale.authentication.UserPassword(server.login, a.password)
//        case a: Configuration.Authentication.KeyFile => gridscale.authentication.PrivateKey(java.io.File(a.file), a.passphrase.getOrElse(""), server.login)
//
//    val auth = toGridscaleAuth(server.authentication)
//    val proxy = server.proxy.map(toGridscaleSSHServer)
//    auth match
//      case a: gridscale.authentication.UserPassword => gridscale.ssh.SSHServer(host = server.host, sshProxy = proxy)(a)
//      case a: gridscale.authentication.PrivateKey => gridscale.ssh.SSHServer(host = server.host, sshProxy = proxy)(a)


  def testWorkerSSH(server: State.Server, retry: Int = 3) =
    try
      execute(server, "echo -n greatings `whoami`", retry)
      ServerState.SSHStatus.ok
    catch
      case e: Exception =>
        ServerState.SSHStatus.failed


case class State(serverPage: State.ServerPage, scriptPage: ScriptPage, tests: List[Configuration.Script], page: State.ServerState.Page = ServerState.Page.server)

enum Msg:
  case UpElement(n: Int = 1)
  case DownElement(n: Int = 1)
  case SSHState(index: Int, status: State.ServerState.SSHStatus)
  case TestState(serverIndex: Int, testIndex: Int, status: State.ServerState.TestStatus)
  case SwitchPage(page: State.ServerState.Page)
  case ExecuteScript(serverIndex: Int, scriptIndex: Int)
  case Executed(serverIndex: Int, scriptIndex: Int, result: State.Server.ExecutionStatus)

class CounterApp(initialState: State) extends LayoutzApp[State, Msg]:
  def init =
    val sshTask =
      initialState.serverPage.server.zipWithIndex.map: (s, i) =>
        Cmd.task {
          val status = State.testWorkerSSH(s.server)
          (i, status)
        } {
          case Right((index, status)) => Msg.SSHState(index, status)
          case Left(m) => ???
        }

    val testTask =
      for
        case (server, serverIndex) <- initialState.serverPage.server.map(_.server).zipWithIndex
        case (test, testIndex) <- initialState.tests.zipWithIndex
      yield
        Cmd.task {
          val status =
            val res = State.execute(server, test.run)
            if res._2 == 0 then State.ServerState.TestStatus.ok else State.ServerState.TestStatus.failed

          (serverIndex, testIndex, status)
        } {
          case Right((serverIndex, testIndex, status)) => Msg.TestState(serverIndex, testIndex, status)
          case Left(m) => Msg.TestState(serverIndex, testIndex, State.ServerState.TestStatus.failed)
        }

    def allTasks = sshTask ++ testTask
    (initialState, Cmd.batch(allTasks*))

  def update(msg: Msg, state: State) =
    msg match
      case Msg.UpElement(n) =>
        state.page match
          case State.ServerState.Page.server => state.focus(_.serverPage.display.selected).modify(s => (s - n).max(0))
          case State.ServerState.Page.script => state.focus(_.scriptPage.display.selected).modify(s => (s - n).max(0))
          case _ => state
      case Msg.DownElement(n) =>
        state.page match
          case State.ServerState.Page.server => state.focus(_.serverPage.display.selected).modify(s => (s + n).min(state.serverPage.server.size - 1))
          case State.ServerState.Page.script => state.focus(_.scriptPage.display.selected).modify(s => (s + n).min(state.scriptPage.script.size - 1))
          case _ => state
      case Msg.SSHState(index, status) => state.focus(_.serverPage.server.index(index).status).replace(status)
      case Msg.TestState(serverIndex, testIndex, status) => state.focus(_.serverPage.server.index(serverIndex).testStatus.index(testIndex)).replace(status)
      case msg@Msg.SwitchPage(page) =>
        state.page match
          case _: State.ServerState.Page.execution => (state.copy(page = page), Cmd.afterMs(500, msg))
          case _ => state.copy(page = page)
      case Msg.Executed(serverIndex, scriptIndex, result) => state.focus(_.serverPage.server.index(serverIndex).executionStatus).replace(result)
      case Msg.ExecuteScript(serverIndex, scriptIndex) =>
          state.serverPage.server(serverIndex) match
            case ss: State.ServerState if ss.status == State.ServerState.SSHStatus.ok && ss.executionStatus.isAvailable =>
              val output = new StringBuilder
              val task =
                Cmd.task {
                  val server = state.serverPage.server(serverIndex).server
                  val script = state.scriptPage.script(scriptIndex)
                  State.execute(server, script.run, out = Some(output))
                } {
                  case Right((out, code)) => Msg.Executed(serverIndex, scriptIndex, State.Server.ExecutionStatus.Executed(scriptIndex, out, code))
                  case Left(m) => Msg.Executed(serverIndex, scriptIndex, State.Server.ExecutionStatus.Error(scriptIndex, m))
                }

              (state.copy(page = State.ServerState.Page.server).focus(_.serverPage.server.index(serverIndex).executionStatus).replace(State.Server.ExecutionStatus.Running(scriptIndex, output)), task)
            case _ =>
              (state.copy(page = State.ServerState.Page.server), Cmd.none)


  def subscriptions(s: State) =
    Sub.onKeyPress:
      case Key.Up => Some(Msg.UpElement(1))
      case Key.Down => Some(Msg.DownElement(1))
      case Key.PageUp => Some(Msg.UpElement(s.serverPage.display.pageSize))
      case Key.PageDown => Some(Msg.DownElement(s.serverPage.display.pageSize))
      case Key.Escape | Key.Char('q') => Some(Msg.SwitchPage(State.ServerState.Page.server))
      case Key.Char('r') => Some(Msg.SwitchPage(State.ServerState.Page.script))
      case Key.Char('e') => Some(Msg.SwitchPage(State.ServerState.Page.execution(s.serverPage.display.selected)))
      case Key.Enter =>
        s.page match
          case State.ServerState.Page.script => Some(Msg.ExecuteScript(s.serverPage.display.selected, s.scriptPage.display.selected))
          case _ => None
      case _             => None

  def view(s: State) =
    def pageView[A](s: Seq[A], displayState: DisplayState) =
      val page = displayState.selected / displayState.pageSize
      val minIndex = page * displayState.pageSize
      val maxIndex = (page + 1) * displayState.pageSize
      s.zipWithIndex.filter((_, index) => index >= minIndex && index < maxIndex)

    def displayServers =
      def servers = pageView(s.serverPage.server, s.serverPage.display)

      layout(
        table(
          Seq("Id", "Name", "IP", "SSH") ++ s.tests.map(_.name),
          servers.map: (w, index) =>
            val status =
              w.status match
                case State.ServerState.SSHStatus.ok => "✔"
                case State.ServerState.SSHStatus.failed => "✗"
                case State.ServerState.SSHStatus.unknown => "↺"

            def testStatus(status: State.ServerState.TestStatus) =
              status match
                case State.ServerState.TestStatus.ok => "✔"
                case State.ServerState.TestStatus.failed => "✗"
                case State.ServerState.TestStatus.unknown => "↺"

            (Seq(s"$index", w.server.name, w.server.host, status) ++ w.testStatus.map(testStatus)).map: e =>
              val text = e
              if s.serverPage.display.selected == index then Color.BrightWhite(text) else text
        )
      )

    def displayScripts =
      def scripts = pageView(s.scriptPage.script, s.scriptPage.display)
      layout(
        table(
          Seq("Id", "Name"),
          scripts.map: (w, index) =>
            Seq(s"$index", w.name).map: e =>
              val text = e.toString
              if s.scriptPage.display.selected == index then Color.BrightWhite(text) else text
        )
      )

    def displayExecution(serverIhdex: Int) =
      val server = s.serverPage.server(serverIhdex)

      def header(server: String, script: Int) =
        underline("─", Color.BrightWhite)(s"Execution of ${s.scriptPage.script(script).name} on $server").center()

      server.executionStatus match
        case State.Server.ExecutionStatus.Nop => layout(banner(s"No execution for ${server.server.name}"))
        case State.Server.ExecutionStatus.Running(index, out) =>
          layout(
            header(server.server.name, index),
            section("Running")(out.toString)
          )
        case State.Server.ExecutionStatus.Error(index, message) =>
          layout(
            header(server.server.name, index),
            s"""Error:
               |${message}""".stripMargin
          )
        case State.Server.ExecutionStatus.Executed(index, out, code) =>
          layout(
            header(server.server.name, index),
            section(s"Finished")(out),
            section(s"Exit code")(s"${code}")
          )

    s.page match
      case State.ServerState.Page.server => displayServers
      case State.ServerState.Page.script => displayScripts
      case e: State.ServerState.Page.execution => displayExecution(e.server)


@main def main(arg: String): Unit =
  def read(file: File) =
    val configuration = yaml.scalayaml.parser.parse(file.contentAsString).toTry.get.as[Configuration].toTry.get

    val duplicated =
      configuration.server.groupBy(_.name).find: (name, servers) =>
        servers.size > 1

    duplicated.foreach: (name, s) =>
      throw new Exception(s"Duplicate server name found: $name")

    val servers = collection.mutable.Map[String, State.Server]()
    configuration.server.foreach: s =>
      val proxy = s.proxy.map: p =>
        servers.getOrElse(p, throw new Exception(s"Proxy server ${p.name} not found for server ${s.name}"))

      val env = s.env.flatMap: e =>
        e.toSeq.map: (k, v) =>
          (k, v)

      servers += s.name -> State.Server(s.name, s.host, s.login, proxy, env)


    val serverState =
      configuration.server.map: s =>
        State.ServerState(servers(s.name), State.ServerState.SSHStatus.unknown, testStatus = configuration.test.map(_ => State.ServerState.TestStatus.unknown).toList)

    State(State.ServerPage(serverState.toList), State.ScriptPage(configuration.script.toList), configuration.test.toList)

  val state = read(File(arg))

  val ec = scala.concurrent.ExecutionContext.fromExecutorService(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
  CounterApp(state).run(executionContext = Some(ec))
