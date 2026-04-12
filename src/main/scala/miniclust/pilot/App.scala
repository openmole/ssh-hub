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
import layoutz.Color.BrightBlack
import miniclust.pilot.CounterApp.serverTestTask
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
  case class SelectionState(row: Set[Int] = Set(), last: Option[Int] = None)
  case class ServerState(server: Server, status: ServerState.SSHStatus, testStatus: Vector[TestStatus], executionStatus: Server.ExecutionStatus = Server.ExecutionStatus.Nop)
  case class ServerPage(server: Vector[State.ServerState], selection: SelectionState = SelectionState(), display: DisplayState = State.DisplayState(40, 0))
  case class ScriptPage(script: Vector[Configuration.Script], display: DisplayState = State.DisplayState(40, 0))

  def retryOnError[T](n: Int)(f: => T, sleep: Int = 100): T =
    try f
    catch
      case e: Exception if n > 0 =>
        Thread.sleep(sleep)
        retryOnError(n - 1)(f)

  def runInNewTerminal(command: String): Unit =
    val os = System.getProperty("os.name").toLowerCase

    val cmd =
      if os.contains("win") then
        Seq("wt.exe", "powershell", "-NoExit", "-Command", command)

      else if os.contains("mac") then
        Seq(
          "osascript", "-e",
          s"""tell application "Terminal" to do script "$command""""
        )

      else
        val terminals =
          List(
            Seq("gnome-terminal", "--", "bash", "-c", s"$command; exec bash"),
            Seq("konsole", "-e", "bash", "-c", s"$command; exec bash"),
            Seq("xterm", "-e", s"$command; bash")
          )

        terminals
          .find(t => existsOnPath(t.head))
          .getOrElse(throw RuntimeException("No supported terminal emulator found"))

    ProcessBuilder(cmd *).start()


  def existsOnPath(bin: String): Boolean =
    sys.env.get("PATH").exists: path =>
      path
        .split(java.io.File.pathSeparator)
        .map(p => java.io.File(p, bin))
        .exists(_.canExecute)


  def sshCommand(server: Server, options: Boolean = true) =
    val proxies = Iterator.iterate(server.proxy)(_.flatMap(_.proxy)).takeWhile(_.isDefined).flatten.toList

    val jumpArgs =
      if proxies.nonEmpty
      then Seq("-J", proxies.map(p => s"${p.login}@${p.host}").mkString(","))
      else Seq()

    val oArgs =
      if options
      then Seq("-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes")
      else Seq()

    Seq("ssh") ++ oArgs ++ jumpArgs ++ Seq(s"${server.login}@${server.host}")

  def execute(server: Server, script: String, retry: Int = 5, out: Option[StringBuilder] = None) =
    import scala.sys.process.*
    val proxies = Iterator.iterate(server.proxy)(_.flatMap(_.proxy)).takeWhile(_.isDefined).flatten.toList

    val jumpArgs =
      if proxies.nonEmpty
      then Seq("-J", proxies.map(p => s"${p.login}@${p.host}").mkString(","))
      else Seq()

    val sshCmd = sshCommand(server) ++ Seq("bash")

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
  case ExecuteScript(serverIndex: Seq[Int], scriptIndex: Int)
  case Executed(serverIndex: Int, scriptIndex: Int, result: State.Server.ExecutionStatus)
  case Refresh
  case RefreshTest(serverIndex: Int)
  case LaunchSSH(serverIndex: Int)
  case SelectRow(row: Int*)

object CounterApp:
  def serverTestTask(index: Int, state: State) =
    val sshTask =
      Cmd.task {
        State.testWorkerSSH(state.serverPage.server(index).server)
      } {
        case Right(status) => Msg.SSHState(index, status)
        case Left(m) => ???
      }

    val testTasks =
      for
        server = state.serverPage.server(index).server
        case (test, testIndex) <- state.tests.zipWithIndex
      yield
        Cmd.task {
          val status =
            val res = State.execute(server, test.run)
            if res._2 == 0 then State.ServerState.TestStatus.ok else State.ServerState.TestStatus.failed

          (testIndex, status)
        } {
          case Right((testIndex, status)) => Msg.TestState(index, testIndex, status)
          case Left(m) => Msg.TestState(index, testIndex, State.ServerState.TestStatus.failed)
        }

    (testTasks ++ Seq(sshTask))


  def testTasks(state: State) =
    def allTasks =
      for serverIndex <- state.serverPage.server.indices
      yield serverTestTask(serverIndex, state)
    allTasks.flatten

class CounterApp(initialState: State) extends LayoutzApp[State, Msg]:

  def init = (initialState, Cmd.batch(CounterApp.testTasks(initialState)*))

  def update(msg: Msg, state: State) =
    msg match
      case Msg.Refresh => state
      case Msg.RefreshTest(index) =>
        val tasks = CounterApp.serverTestTask(index, state)
        val newState =
          state
            .focus(_.serverPage.server.index(index).status).replace(State.ServerState.SSHStatus.unknown)
            .focus(_.serverPage.server.index(index).testStatus).replace(Vector.fill(state.tests.size)(State.ServerState.TestStatus.unknown))
        (newState, Cmd.batch(tasks*))
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
          case _: State.ServerState.Page.execution => (state.copy(page = page), Cmd.afterMs(500, Msg.Refresh))
          case _ => state.copy(page = page)
      case Msg.Executed(serverIndex, scriptIndex, result) => state.focus(_.serverPage.server.index(serverIndex).executionStatus).replace(result)
      case Msg.LaunchSSH(serverIndex) =>
        State.runInNewTerminal(State.sshCommand(state.serverPage.server(serverIndex).server, options = false).mkString(" "))
        state
      case Msg.SelectRow(r*) =>
        if r.isEmpty
        then state.focus(_.serverPage.selection).replace(State.SelectionState())
        else
          state.focus(_.serverPage.selection).modify: s =>
            if r.size == 1
            then
              if s.row.contains(r.head)
              then s.copy(s.row - r.head, last = None)
              else s.copy(s.row + r.head, last = r.lastOption)
            else s.copy(s.row ++ r, last = r.lastOption)
      case Msg.ExecuteScript(serverIndexes, scriptIndex) =>
        def available(serverIndex: Int): Option[Int] =
          state.serverPage.server(serverIndex) match
            case ss: State.ServerState if ss.status == State.ServerState.SSHStatus.ok && ss.executionStatus.isAvailable => Some(serverIndex)
            case _ => None

        def execute(state: State, serverIndex: Int, cmd: Seq[Cmd[Msg]]): (State, Seq[Cmd[Msg]]) =
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

          val newState = state.focus(_.serverPage.server.index(serverIndex).executionStatus).replace(State.Server.ExecutionStatus.Running(scriptIndex, output))
          (newState, cmd :+ task)

        val servers = serverIndexes.flatMap(available)
        val (newState, cmd) = servers.foldLeft((state, Seq[Cmd[Msg]]())) { case ((s, cmd), index) => execute(s, index, cmd) }
        (newState.copy(page = State.ServerState.Page.server), Cmd.batch(cmd*))

  def subscriptions(s: State) =
    Sub.onKeyPress:
      case Key.Up => Some(Msg.UpElement(1))
      case Key.Down => Some(Msg.DownElement(1))
      case Key.PageUp => Some(Msg.UpElement(s.serverPage.display.pageSize))
      case Key.PageDown => Some(Msg.DownElement(s.serverPage.display.pageSize))
      case Key.Char('q') => Some(Msg.SwitchPage(State.ServerState.Page.server))
      case Key.Escape =>
        s.page match
          case State.ServerState.Page.server => Some(Msg.SelectRow())
          case _ => Some(Msg.SwitchPage(State.ServerState.Page.server))
      case Key.Char('s') => Some(Msg.SelectRow(s.serverPage.display.selected))
      case Key.Char('T') =>
        s.page match
          case State.ServerState.Page.server => Some(Msg.LaunchSSH(s.serverPage.display.selected))
          case _ => None
      case Key.Char('r') => Some(Msg.SwitchPage(State.ServerState.Page.script))
      case Key.Char('e') => Some(Msg.SwitchPage(State.ServerState.Page.execution(s.serverPage.display.selected)))
      case Key.Char('t') =>
        s.page match
          case State.ServerState.Page.server => Some(Msg.RefreshTest(s.serverPage.display.selected))
          case _ => None
      case Key.Enter =>
        s.page match
          case State.ServerState.Page.script =>
            if s.serverPage.selection.row.isEmpty
            then Some(Msg.ExecuteScript(Seq(s.serverPage.display.selected), s.scriptPage.display.selected))
            else Some(Msg.ExecuteScript(s.serverPage.selection.row.toSeq, s.scriptPage.display.selected))
          case _ => None
      case _             => None

  def view(s: State) =
    def pageView[A](s: Seq[A], displayState: DisplayState) =
      val page = displayState.selected / displayState.pageSize
      val minIndex = page * displayState.pageSize
      val maxIndex = (page + 1) * displayState.pageSize
      s.zipWithIndex.filter((_, index) => index >= minIndex && index < maxIndex)

    def footer: Element =
      val hint =
        s.page match
          case State.ServerState.Page.server =>
            """↑/↓ navigate  'r' Run Script Page  'e' Show Execution  't' Test Server  'T' SSH Terminal  's' Select Server  Ctrl+Q quit""".stripMargin
          case State.ServerState.Page.script => "↑/↓ navigate  'enter' Run Script  'q' Server Page  Ctrl+Q quit"
          case e: State.ServerState.Page.execution => "'q' Server Page  Ctrl+Q quit"

      hint.color(Color.BrightWhite).style(Style.Dim)


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
              val line: Element =
                if s.serverPage.display.selected == index
                then Color.BrightWhite(text)
                else text
              if s.serverPage.selection.row.contains(index)
              then line.style(Style.Bold).bg(BrightBlack)
              else line
        ),
        footer
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
        ),
        footer
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
            section("Running")(out.toString),
            footer
          )
        case State.Server.ExecutionStatus.Error(index, message) =>
          layout(
            header(server.server.name, index),
            s"""Error:
               |${message}""".stripMargin,
            footer
          )
        case State.Server.ExecutionStatus.Executed(index, out, code) =>
          layout(
            header(server.server.name, index),
            section(s"Finished")(out),
            section(s"Exit code")(s"${code}"),
            footer
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
        State.ServerState(servers(s.name), State.ServerState.SSHStatus.unknown, testStatus = configuration.test.map(_ => State.ServerState.TestStatus.unknown).toVector)

    State(State.ServerPage(serverState.toVector), State.ScriptPage(configuration.script.toVector), configuration.test.toList)

  val state = read(File(arg))

  val ec = scala.concurrent.ExecutionContext.fromExecutorService(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
  CounterApp(state).run(executionContext = Some(ec))
