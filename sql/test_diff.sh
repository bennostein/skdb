#!/bin/bash

DB=/tmp/test.db

if [ -z "$SKARGO_PROFILE" ]; then
    SKARGO_PROFILE=dev
fi

SKDB_CMD="skargo run -q --profile $SKARGO_PROFILE -- "
SKDB="$SKDB_CMD --always-allow-joins --data $DB"

pass() { printf "%-50s OK\n" "$1:"; }
fail() { printf "%-50s FAILED\n" "$1:"; }

run_diff () {
    use_sqlite=true
    if [ "$1" = "--no-sqlite" ]; then
        use_sqlite=false
        shift
    fi
    creation_script=$1
    shift
    views_script=$1
    shift
    more_scripts=("$@")

    rm -f /tmp/kk1 /tmp/kk2 /tmp/kk3 $DB

    nviews=$(cat "$views_script" | grep VIEW | sed 's/CREATE REACTIVE VIEW V//' | sed 's/ .*//' | sort -n -r | head -n 1)

    $SKDB_CMD --init $DB
    cat "$creation_script" "$views_script" | $SKDB

    for i in $(seq 0 $((nviews))); do
        rm -f "/tmp/V$i"
        $SKDB subscribe "V$i" --connect --updates "/tmp/V$i" > /dev/null &
    done

    wait

    cat "${more_scripts[@]}" | $SKDB

    rm -f /tmp/selects.sql

    for i in $(seq 0 $((nviews))); do
        echo "select * from V$i;"
    done > /tmp/selects.sql;

    rm -f /tmp/replays

    wait

    for i in $(seq 0 $((nviews))); do
        cat "/tmp/V$i" | $SKDB_CMD replay >> /tmp/replays
    done;

    cat /tmp/selects.sql | $SKDB | sort -n > /tmp/kk1

    if $use_sqlite; then
        cat "$views_script" | sed 's/CREATE REACTIVE VIEW V[0-9]* AS //' > /tmp/selects2.sql

        cat "$creation_script" "${more_scripts[@]}" /tmp/selects2.sql | sqlite3 | sort -n > /tmp/kk2

        diff /tmp/kk1 /tmp/kk2
        if [ $? -eq 0 ]; then
            pass "$views_script (part-1)"
        else
            fail "$views_script (part-1)"
        fi
    fi

    cat /tmp/replays | sort -n > /tmp/kk3

    diff /tmp/kk1 /tmp/kk3 > /dev/null
    if [ $? -eq 0 ]; then
        pass "$views_script (part-2)"
    else
        fail "$views_script (part-2)"
    fi

}

run_diff 'test/diff/select2_create.sql' 'test/diff/select2_min_views.sql' 'test/diff/select2_inserts.sql'
run_diff 'test/diff/select2_create.sql' 'test/diff/select2_min_views.sql' 'test/diff/select2_inserts.sql' 'test/diff/select2_deletes.sql'
run_diff 'test/diff/select2_create.sql' 'test/diff/select2_min_views.sql' 'test/diff/select2_inserts.sql' 'test/diff/select2_deletes.sql' 'test/diff/select2_inserts.sql'
run_diff 'test/diff/select1_create.sql' 'test/diff/select1_views.sql' 'test/diff/select1_inserts.sql'
run_diff 'test/diff/select1_float_create.sql' 'test/diff/select1_float_views.sql' 'test/diff/select1_float_inserts.sql'
run_diff 'test/diff/select2_create.sql' 'test/diff/select2_views.sql' 'test/diff/select2_inserts.sql'
run_diff 'test/diff/select3_create.sql' 'test/diff/select3_views.sql' 'test/diff/select3_inserts.sql'
run_diff 'test/diff/select3_create.sql' 'test/diff/select3_views.sql' 'test/diff/select3_inserts.sql' 'test/diff/select3_partial_delete.sql'
run_diff 'test/diff/select4.1-create.sql' 'test/diff/select4.1-views.sql' 'test/diff/select4.1-inserts.sql'
run_diff 'test/diff/select5.1-create.sql' 'test/diff/select5.1-views.sql' 'test/diff/select5.1-inserts.sql'
run_diff 'test/diff/groupby_create.sql' 'test/diff/groupby_views.sql' 'test/diff/groupby_inserts.sql'
run_diff 'test/diff/groupby_create.sql' 'test/diff/groupby_views.sql' 'test/diff/groupby_inserts.sql' 'test/diff/groupby_delete.sql'
run_diff 'test/diff/slt_good_0_create.sql' 'test/diff/slt_good_0_views.sql' 'test/diff/slt_good_0_inserts.sql'

# Same tests, but with a limit of 1

run_diff --no-sqlite 'test/diff/select2_create.sql' 'test/diff/select2_min_views_limit1.sql' 'test/diff/select2_inserts.sql'
run_diff --no-sqlite 'test/diff/select2_create.sql' 'test/diff/select2_min_views_limit1.sql' 'test/diff/select2_inserts.sql' 'test/diff/select2_deletes.sql'
run_diff --no-sqlite 'test/diff/select2_create.sql' 'test/diff/select2_min_views_limit1.sql' 'test/diff/select2_inserts.sql' 'test/diff/select2_deletes.sql' 'test/diff/select2_inserts.sql'
run_diff --no-sqlite 'test/diff/select1_create.sql' 'test/diff/select1_views_limit1.sql' 'test/diff/select1_inserts.sql'
run_diff --no-sqlite 'test/diff/select1_float_create.sql' 'test/diff/select1_float_views_limit1.sql' 'test/diff/select1_float_inserts.sql'
run_diff --no-sqlite 'test/diff/select2_create.sql' 'test/diff/select2_views_limit1.sql' 'test/diff/select2_inserts.sql'
run_diff --no-sqlite 'test/diff/select3_create.sql' 'test/diff/select3_views_limit1.sql' 'test/diff/select3_inserts.sql'
run_diff --no-sqlite 'test/diff/select3_create.sql' 'test/diff/select3_views_limit1.sql' 'test/diff/select3_inserts.sql' 'test/diff/select3_partial_delete.sql'
run_diff --no-sqlite 'test/diff/select4.1-create.sql' 'test/diff/select4.1-views_limit1.sql' 'test/diff/select4.1-inserts.sql'
run_diff --no-sqlite 'test/diff/select5.1-create.sql' 'test/diff/select5.1-views_limit1.sql' 'test/diff/select5.1-inserts.sql'
run_diff --no-sqlite 'test/diff/groupby_create.sql' 'test/diff/groupby_views_limit1.sql' 'test/diff/groupby_inserts.sql'
run_diff --no-sqlite 'test/diff/groupby_create.sql' 'test/diff/groupby_views_limit1.sql' 'test/diff/groupby_inserts.sql' 'test/diff/groupby_delete.sql'
run_diff --no-sqlite 'test/diff/slt_good_0_create.sql' 'test/diff/slt_good_0_views_limit1.sql' 'test/diff/slt_good_0_inserts.sql'

# Same tests, but with a limit of 5

run_diff --no-sqlite 'test/diff/select2_create.sql' 'test/diff/select2_min_views_limit5.sql' 'test/diff/select2_inserts.sql'
run_diff --no-sqlite 'test/diff/select2_create.sql' 'test/diff/select2_min_views_limit5.sql' 'test/diff/select2_inserts.sql' 'test/diff/select2_deletes.sql'
run_diff --no-sqlite 'test/diff/select2_create.sql' 'test/diff/select2_min_views_limit5.sql' 'test/diff/select2_inserts.sql' 'test/diff/select2_deletes.sql' 'test/diff/select2_inserts.sql'
run_diff --no-sqlite 'test/diff/select1_create.sql' 'test/diff/select1_views_limit5.sql' 'test/diff/select1_inserts.sql'
run_diff --no-sqlite 'test/diff/select1_float_create.sql' 'test/diff/select1_float_views_limit5.sql' 'test/diff/select1_float_inserts.sql'
run_diff --no-sqlite 'test/diff/select2_create.sql' 'test/diff/select2_views_limit5.sql' 'test/diff/select2_inserts.sql'
run_diff --no-sqlite 'test/diff/select3_create.sql' 'test/diff/select3_views_limit5.sql' 'test/diff/select3_inserts.sql'
run_diff --no-sqlite 'test/diff/select3_create.sql' 'test/diff/select3_views_limit5.sql' 'test/diff/select3_inserts.sql' 'test/diff/select3_partial_delete.sql'
run_diff --no-sqlite 'test/diff/select4.1-create.sql' 'test/diff/select4.1-views_limit5.sql' 'test/diff/select4.1-inserts.sql'
run_diff --no-sqlite 'test/diff/select5.1-create.sql' 'test/diff/select5.1-views_limit5.sql' 'test/diff/select5.1-inserts.sql'
run_diff --no-sqlite 'test/diff/groupby_create.sql' 'test/diff/groupby_views_limit5.sql' 'test/diff/groupby_inserts.sql'
run_diff --no-sqlite 'test/diff/groupby_create.sql' 'test/diff/groupby_views_limit5.sql' 'test/diff/groupby_inserts.sql' 'test/diff/groupby_delete.sql'
run_diff --no-sqlite 'test/diff/slt_good_0_create.sql' 'test/diff/slt_good_0_views_limit5.sql' 'test/diff/slt_good_0_inserts.sql'
