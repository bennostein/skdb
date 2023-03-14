#!/bin/bash

trap "trap - SIGTERM && kill -- -$$" SIGINT SIGTERM EXIT

db=$(mktemp /tmp/db.XXXXXX)
tailfile=$(mktemp /tmp/tail.XXXXXX)
file1=$(mktemp /tmp/file.XXXXXX)
file2=$(mktemp /tmp/file.XXXXXX)

rm -Rf $db $tailfile $file1 $file2

./target/skdb --init $db
skdb="./target/skdb --data $db"

cat privacy/init.sql | $skdb
echo "create table t1 (a INTEGER);" | $skdb
echo "create table t2 (a INTEGER);" | $skdb

sessionID=`$skdb subscribe t1 --connect`

# Staring the tailer
tail -f /dev/null | $skdb tail $sessionID --follow > $tailfile&
tailerID=$!

# First let's insert something

echo "insert into t1 values(0);" | $skdb

# And make sure that the tailer picked it up

while ! grep "0" $tailfile -q > /dev/null; do
    sleep 1
done

# Let's add a lot of the same data

for i in {1..100}; do
    echo "insert into t1 values(1);"
done | $skdb

echo "insert into t1 values(-1);" | $skdb

# Let's make sure a purge actually occurred.

while ! cat $tailfile | tr '\t' 'Y' | egrep '[-]1' -q > /dev/null; do
    sleep 1
done

wc $tailfile

cat $tailfile | $skdb write-csv t2 > /dev/null

echo "select * from t1;" | $skdb > $file1
echo "select * from t2;" | $skdb > $file2

diff $file1 $file2 > /dev/null
if [ $? -eq 0 ]; then
    echo "PURGE TEST: OK"
else
    echo "PURGE TEST: FAILED"
fi

rm -Rf $db $tailfile $file1 $file2

kill $(jobs -rp)
wait $(jobs -rp) 2>/dev/null
