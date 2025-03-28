class MatchCheck:
    def __init__(self, colnames):
        self.clauses = []
        self.colnames = colnames

    def clause(self, schedPred, rows):
        self.clauses.append((schedPred, rows))
        return self

    def elze(self, rows):
        return self.clause(lambda _: True, rows)

    def __call__(self, resultSet, schedule):
        for pred, rows in self.clauses:
            if pred(schedule):
                match = list(
                    {k: v for (k, v) in zip(self.colnames, row)} for row in rows
                )
                if resultSet != match:
                    return f"{resultSet} did not match expected: {match}"
                return ""
        return "no clauses matched schedule"


class Expectations:
    def __init__(self):
        self.checks = []

    def __str__(self):
        return f"expectations"

    def __repr__(self):
        return f"expectations"

    def equals(self, *rows, colnames=[]):
        match = list({k: v for (k, v) in zip(colnames, row)} for row in rows)

        def check(resultSet, _schedule):
            if resultSet != match:
                return f"{resultSet} did not match expected: {match}"
            return ""

        self.checks.append(check)

    def isOneOf(self, setOfRows, colnames=[]):
        def check(resultSet, _schedule):
            for rows in setOfRows:
                match = list(
                    {k: v for (k, v) in zip(colnames, row)} for row in rows
                )
                if resultSet == match:
                    return ""
            return f"{resultSet} did not match any of: {setOfRows}"

        self.checks.append(check)

    def match(self, colnames=[]):
        check = MatchCheck(colnames)
        self.checks.append(check)
        return check

    def _verifyChecks(self, peerResultMap, schedule):
        # assumption: only do this for first peer because we've verified convergence
        it = iter(peerResultMap.items())
        peer, resultSet = next(it)
        for check in self.checks:
            msg = check(resultSet, schedule)
            if msg != "":
                return f"{peer}: {msg}"
        return ""

    def _verifyConvergence(self, peerResultMap):
        it = iter(peerResultMap.items())
        firstPeer, firstResultSet = next(
            it
        )  # do not catch, there should be one result
        for peer, resultSet in it:
            if resultSet != firstResultSet:
                return (
                    f"{firstPeer}: {firstResultSet} != {resultSet} from {peer}"
                )
        return ""

    def check(self, peerResultMap, schedule):
        firstFailure = self._verifyConvergence(peerResultMap)
        if firstFailure != "":
            raise AssertionError(firstFailure)
        firstFailure = self._verifyChecks(peerResultMap, schedule)
        if firstFailure != "":
            raise AssertionError(firstFailure)
