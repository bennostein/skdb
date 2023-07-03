from __future__ import annotations
from collections import defaultdict
import itertools
import random
import copy
import sys
import asyncio

task_id_counter = 0

async def nop(*args, **kwargs):
  pass

class Task:
  def __init__(self, name, fn, final = nop):
    self.name = name
    self.fn = fn
    self.final = final
    global task_id_counter
    self.uid = task_id_counter
    task_id_counter = task_id_counter + 1

  def __repr__(self):
    return f"{self.name}"

  def __str__(self):
    return f"{self.name}"

  def __hash__(self) -> int:
    return hash(self.uid)

  def __eq__(self, value: Task) -> bool:
    return self.uid == value.uid

  async def run(self, schedule):
    await self.fn(schedule)

  async def finalise(self, schedule):
    await self.final(schedule)

class MutableCompositeTask:
  def __init__(self):
    self.taskSeq = []

    global task_id_counter
    self.uid = task_id_counter
    task_id_counter = task_id_counter + 1

  def add(self, task):
    self.taskSeq.append(task)
    return self

  def __repr__(self):
    return " then ".join(str(x) for x in self.taskSeq)

  def __str__(self):
    return " then ".join(str(x) for x in self.taskSeq)

  def __hash__(self) -> int:
    return hash(self.uid)

  def __eq__(self, value: Task) -> bool:
    return self.uid == value.uid

  async def run(self, schedule):
    for t in self.taskSeq:
      await t.run(schedule)

  async def finalise(self, schedule):
    for t in reversed(self.taskSeq):
      await t.finalise(schedule)

class Scheduler:
  def __init__(self):
    self.tasks = []
    self.graph = defaultdict(list)

  def add(self, task):
    self.tasks.append(task)

  def happensBefore(self, a, b):
    self.graph[a].append(b)

  def schedules(self):
    return []

  async def run(self):
    async def _run(schedule):
      try:
        await schedule.run()
      except AssertionError as err:
        print(f"Assertion check failed running {schedule}")
        print(err)
        sys.exit(1)
      finally:
        for t in reversed(self.tasks):
          await t.finalise(schedule)

    n = 0
    tasks = set()
    for i, schedule in enumerate(self.schedules()):
      n = i
      tasks.add(asyncio.create_task(_run(schedule)))
      # 16 is fairly arbitrary. a few experimental runs suggests it's
      # quite good on an m1 macbook. this whole batch gather model
      # isn't great, but it's easy to code and good enough to run
      # hundreds of schedules in a few secs.
      if i % 16 == 0:
        await asyncio.gather(*tasks)
        tasks = set()
    await asyncio.gather(*tasks)
    print(f"Ran {n+1} schedules, all PASSED expectation checks")

class Schedule:
  def __init__(self, tasks):
    self.state = {}
    self.tasks = tasks

  def storeScheduleLocal(self, key, value):
    self.state[key] = value

  def getScheduleLocal(self, key):
    return self.state.get(key)

  def __repr__(self):
    lst = "\n".join(f"{i}: {x}" for i,x in enumerate(self.tasks))
    return f"schedule:\n{lst}"

  def __str__(self):
    return self.__repr__()

  async def run(self):
    for t in self.tasks:
      await t.run(self)

class ArbitraryTopoSortScheduler(Scheduler):
  def schedules(self):
    # kahn's algo, we pick from multiple choices arbitrarily

    g = copy.deepcopy(self.graph)

    def nodesWithNoIncomingEdge():
      acc = set()
      for node in self.tasks:
        found = False
        for (_, nodes) in g.items():
          if node in nodes:
            found = True
            break
        if not found:
          acc.add(node)
      return acc

    schedule = []
    candidates = nodesWithNoIncomingEdge()
    while candidates != set():
      n = candidates.pop()
      schedule.append(n)
      g[n] = list()
      candidates = candidates.union(nodesWithNoIncomingEdge()).difference(set(schedule))

    def hasEdges(g):
      any(x != list() for x in g.values())

    if hasEdges(g):
      raise RuntimeError("Graph had a cycle")

    yield Schedule(schedule)

class AllTopoSortsScheduler(Scheduler):
  def __init__(self, limit = 100, runAll = False):
    super().__init__()
    self.limit = limit
    self.runAll = runAll

  def _nodesWithNoIncomingEdge(self, g):
    acc = set()
    for node in self.tasks:
      found = False
      for (_, nodes) in g.items():
        if node in nodes:
          found = True
          break
      if not found:
        acc.add(node)
    return acc

  def _schedules(self, schedule, candidates, g):
    if candidates == set():
      if any(x != list() for x in g.values()):
        raise RuntimeError("Graph had a cycle")
      yield Schedule(schedule)
      return

    for n in candidates:
      ourCandidates = copy.deepcopy(candidates)
      ourSchedule = copy.deepcopy(schedule)
      ourG = copy.deepcopy(g)
      ourSchedule.append(n)
      ourG[n] = list()
      ourCandidates = ourCandidates.union(self._nodesWithNoIncomingEdge(ourG)).difference(set(ourSchedule))
      for s in self._schedules(ourSchedule, ourCandidates, ourG):
        yield s

  def schedules(self):
    schedule = []
    candidates = self._nodesWithNoIncomingEdge(self.graph)
    return self._schedules(schedule, candidates, self.graph)

  async def run(self):
    if not self.runAll:
      i = 0
      for _ in self.schedules():
        i = i+1
        if i > self.limit:
          raise RuntimeError(f"There are more than {self.limit} schedules")

    await super().run()

class RandomTopoSortsScheduler(Scheduler):
  def __init__(self, scheduler, N):
    self.scheduler = scheduler
    self.N = N

  def schedules(self):
    # just simple reservoir sample
    schedules = self.scheduler.schedules()
    reservoir = list(itertools.islice(schedules, self.N))
    i = self.N - 1
    for schedule in schedules:
      i = i + 1
      randIdx = random.randint(0, i)
      if randIdx < self.N:
        reservoir[randIdx] = schedule
    print(f"We will run {self.N} of the {i+1} possible schedules. ~{int((self.N)/(i+1.0)*100)}%")
    return reservoir
