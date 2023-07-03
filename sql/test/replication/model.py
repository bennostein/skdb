import asyncio
import uuid
from collections import defaultdict, deque
from scheduling import Task, MutableCompositeTask
import os
import json

SKDB = "/skfs/build/skdb"
INITSQL = "/skfs/sql/privacy/init.sql"

def serialise(val):
  if isinstance(val, str):
    return f"'{val}'"
  return str(val)

def createNativeDb(dbkey, schemaQueries):
  async def f(schedule):
    guid = uuid.uuid4()
    db = f"/tmp/{guid}.db"
    schedule.storeScheduleLocal(dbkey, db)

    proc = await asyncio.create_subprocess_exec(SKDB, "--init", db)
    exit = await proc.wait()
    if exit > 0:
      raise RuntimeError("init exited non-zero")

    # TODO: should not do on client
    init = open(INITSQL)
    proc = await asyncio.create_subprocess_exec(SKDB, "--data", db, stdin=init)
    exit = await proc.wait()
    init.close()
    if exit > 0:
      raise RuntimeError("init exited non-zero")

    qs = "\n".join(schemaQueries)
    proc = await asyncio.create_subprocess_exec(SKDB, "--data", db,
                                                stdin=asyncio.subprocess.PIPE)
    await proc.communicate(qs.encode())
    if proc.returncode is None or proc.returncode > 0:
      raise RuntimeError("init exited non-zero")
  return f

def destroyNativeDb(dbkey):
  async def f(schedule):
    db = schedule.getScheduleLocal(dbkey)
    if db is None:
      raise RuntimeError("could not get db")
    os.remove(db)
  return f

def runDmlQuery(dbkey, query):
  async def f(schedule):
    db = schedule.getScheduleLocal(dbkey)
    if db is None:
      raise RuntimeError("could not get db")

    proc = await asyncio.create_subprocess_exec(SKDB, "--data", db,
                                                stdin=asyncio.subprocess.PIPE)
    await proc.communicate(query.encode())
    if proc.returncode is None or proc.returncode > 0:
      raise RuntimeError(f"running '{query}' exited non-zero")
  return f

def runQuery(dbkey, query):
  async def f(schedule):
    db = schedule.getScheduleLocal(dbkey)
    if db is None:
      raise RuntimeError("could not get db")

    proc = await asyncio.create_subprocess_exec(SKDB, "--data", db, "--format=json",
                                                stdin=asyncio.subprocess.PIPE,
                                                stdout=asyncio.subprocess.PIPE)
    (out, _) = await proc.communicate(query.encode())
    if proc.returncode is None or proc.returncode > 0:
      raise RuntimeError(f"running '{query}' exited non-zero")

    lines = out.decode().split('\n')
    return (json.loads(x) for x in lines if x.strip() != '')
  return f

def subscribe(dbkey, subkey, table, user, replicationId):
  async def f(schedule):
    db = schedule.getScheduleLocal(dbkey)
    if db is None:
      raise RuntimeError("could not get db")
    proc = await asyncio.create_subprocess_exec(SKDB, "--data", db, "subscribe", table, "--connect",
                                                # TODO:
                                                # "--user", user,
                                                "--ignore-source", replicationId,
                                                stdout=asyncio.subprocess.PIPE)
    (out, _) = await proc.communicate()
    session = out.decode().rstrip()
    schedule.storeScheduleLocal(subkey, session)
    return session
  return f

async def tail(db, session, since):
  proc = await asyncio.create_subprocess_exec(SKDB, "--data", db, "tail",
                                              "--format=csv", session, "--since", str(since),
                                              stdout=asyncio.subprocess.PIPE)
  (out, _) = await proc.communicate()
  return out

def startStreamingWriteCsv(dbkey, writecsvKey, table, user, replicationId):
  async def f(schedule):
    db = schedule.getScheduleLocal(dbkey)
    if db is None:
      raise RuntimeError("could not get db")

    proc = await asyncio.create_subprocess_exec(SKDB, "--data", db, "write-csv", table,
                                                # TODO:
                                                # "--user", user,
                                                "--source", replicationId,
                                                stdin=asyncio.subprocess.PIPE,
                                                stdout=asyncio.subprocess.DEVNULL,)
    schedule.storeScheduleLocal(writecsvKey, proc)
    return proc
  return f

def getLastCheckpoint(current, diffOutput):
  lines = diffOutput.split('\n')
  cps = [int(l.removeprefix(':')) for l in lines if l.startswith(":")]
  cps.append(current)
  cps.append(0)
  return max(cps)

class HalfStream:

  def __init__(self, sender, receiver, sendTask, recvTask):
    self.sender = sender
    self.receiver = receiver
    self.sendTaskFactory = sendTask
    self.recvTaskFactory = recvTask

  def __repr__(self):
    return f"<{self.sender} -> {self.receiver}>"

  def __str__(self):
    return f"<{self.sender} -> {self.receiver}>"

  def send(self, schedule, payload):
    schedule.getScheduleLocal(self).append(payload)
    return self

  def recv(self, schedule):
    payload =  schedule.getScheduleLocal(self).pop()
    return payload

  def initTask(self):
    t = MutableCompositeTask()
    send = self.sendTaskFactory(self, init=True)
    t.add(send)
    recv = self.recvTaskFactory(self, init=True)
    t.add(recv)
    async def f(schedule):
      schedule.storeScheduleLocal(self, deque())
    t.add(Task(f"create {self} channel buffer", f))
    return t

  def clockTask(self):
    # originally we added the two tasks separtely with a
    # happens-before relation, but this blows up the number of
    # schedules
    t = MutableCompositeTask()
    send = self.sendTaskFactory(self, init=False)
    t.add(send)
    recv = self.recvTaskFactory(self, init=False)
    t.add(recv)
    return t

class SkdbPeer:

  def __init__(self, name, scheduler):
    self.schema = ['foo']
    self.streams = defaultdict(list)
    self.lastTask: None|Task|MutableCompositeTask = None
    self.name = name
    self.scheduler = scheduler

  def __repr__(self):
    return self.name

  def __str__(self):
    return self.name

  def notifyConnection(self, table, stream: HalfStream):
    self.streams[table].append(stream)
    return self

  def insertInto(self, table: str, row):
    rowStr = ", ".join(serialise(val) for val in row)
    q = f"INSERT INTO {table} VALUES ({rowStr});"
    insert = Task(f"insert {row} in to '{table}' on {self}", runDmlQuery(self, q))
    self.scheduler.add(insert)
    self.scheduler.happensBefore(self.lastTask, insert)
    self.lastTask = insert
    # TODO: this will need to traverse the graph of connections, not just the current single hop
    for stream in self.streams[table]:
      send = stream.clockTask()
      self.scheduler.add(send)
      self.scheduler.happensBefore(insert, send)
    return insert

  async def query(self, schedule, query):
    return await runQuery(self, query)(schedule)

  def initTask(self) -> Task:
    raise NotImplementedError()

  def tailTask(self, table, replicationId):
    def factory(stream, init):
      subkey = (self, stream, 'sub')

      async def start(schedule):
        user = "todo"               # TODO:
        start = subscribe(self, subkey, table, user, replicationId)
        await start(schedule)

      async def pull(schedule):
        sinceKey = (self, stream, 'since')
        db = schedule.getScheduleLocal(self)
        session = schedule.getScheduleLocal(subkey)
        since = schedule.getScheduleLocal(sinceKey) or 0
        payload = await tail(db, session, since)
        stream.send(schedule, payload)
        schedule.storeScheduleLocal(sinceKey, getLastCheckpoint(since, payload.decode()))

      if init:
        return Task(f"create subscription for {self} {table} {stream}", start)
      return Task(f"read {table} tail from {self} and send to {stream}", pull)
    return factory

  def writeTask(self, table, replicationId):
    def factory(stream, init):
      key = (self, stream, 'write')
      async def start(schedule):
        user = "todo"               # TODO:
        start = startStreamingWriteCsv(self, key, table, user, replicationId)
        await start(schedule)

      async def stop(schedule):
        proc = schedule.getScheduleLocal(key)
        proc.terminate()

      async def push(schedule):
        proc = schedule.getScheduleLocal(key)

        payload = stream.recv(schedule)
        proc.stdin.write(payload)

      if init:
        return Task(f"start write-csv for {self} {table} {stream}", start, stop)
      return Task(f"read from {stream} and write to {self} {table}", push)
    return factory

class Server(SkdbPeer):

  def initTask(self) -> Task:
    return Task(f"create server {self.name}",
                createNativeDb(self, self.schema),
                destroyNativeDb(self))

class Client(SkdbPeer):

  def initTask(self) -> Task:
    return Task(f"create client {self.name}",
                createNativeDb(self, self.schema),
                destroyNativeDb(self))

class Topology:

  def __init__(self, scheduler):
    self.replicationIdGen = 0
    self.schemaQueries = []
    self.peers = []
    self.scheduler = scheduler
    self.initTask = MutableCompositeTask()
    self.scheduler.add(self.initTask)

  def schema(self, query):
    self.schemaQueries.append(query)
    return self

  def add(self, peer: SkdbPeer):
    self.peers.append(peer)
    peer.schema = self.schemaQueries
    self.initTask.add(peer.initTask())
    peer.lastTask = self.initTask
    return peer

  def _genReplicationId(self):
    self.replicationIdGen = self.replicationIdGen + 1
    return str(self.replicationIdGen)

  def mirror(self, table, a, b):
    repId = self._genReplicationId()
    atob = HalfStream(a, b, a.tailTask(table, repId), b.writeTask(table, repId))
    repId = self._genReplicationId()
    btoa = HalfStream(b, a, b.tailTask(table, repId), a.writeTask(table, repId))
    self.initTask.add(atob.initTask())
    self.initTask.add(btoa.initTask())
    a.notifyConnection(table, atob)
    b.notifyConnection(table, btoa)
    return self

  def now(self, query: str):
    expectations = Expectations()
    async def f(schedule):
      results = await asyncio.gather(
        *[peer.query(schedule, query) for peer in self.peers]
      )
      expectations.check(results)
    checkTask = Task(f"Check {expectations} on {query}", f)
    for scheduled in self.scheduler.tasks:
      self.scheduler.happensBefore(scheduled, checkTask)
    self.scheduler.add(checkTask)
    return expectations

class Expectations():
  def hasRows(self, *rows):
    pass

  def check(self, resultSets):
    pass
