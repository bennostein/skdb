import type {
  Accumulator,
  ColumnSchema,
  Index,
  Schema,
  Opt,
} from "./skipruntime_api.js";

export class Sum implements Accumulator<number, number> {
  default = 0;

  accumulate(acc: number, value: number): number {
    return acc + value;
  }

  dismiss(acc: number, value: number): Opt<number> {
    return acc - value;
  }
}

export class Min implements Accumulator<number, number> {
  default = null;

  accumulate(acc: Opt<number>, value: number): number {
    return acc === null ? value : Math.min(acc, value);
  }

  dismiss(acc: number, value: number): Opt<number> {
    return value > acc ? acc : null;
  }
}

export class Max implements Accumulator<number, number> {
  default = null;

  accumulate(acc: Opt<number>, value: number): number {
    return acc === null ? value : Math.max(acc, value);
  }

  dismiss(acc: number, value: number): Opt<number> {
    return value < acc ? acc : null;
  }
}

export function schema(
  name: string,
  expected: ColumnSchema[],
  indexes?: Index[],
): Schema {
  return {
    name,
    expected,
    indexes,
  };
}

export function cinteger(
  name: string,
  notnull: boolean = true,
  primary: boolean = false,
): ColumnSchema {
  return {
    name,
    type: "INTEGER",
    notnull,
    primary,
  };
}

export function ctext(
  name: string,
  notnull: boolean = true,
  primary: boolean = false,
): ColumnSchema {
  return {
    name,
    type: "TEXT",
    notnull,
    primary,
  };
}

export function cjson(
  name: string,
  notnull: boolean = true,
  primary: boolean = false,
): ColumnSchema {
  return {
    name,
    type: "JSON",
    notnull,
    primary,
  };
}

export function cfloat(
  name: string,
  notnull: boolean = true,
  primary: boolean = false,
): ColumnSchema {
  return {
    name,
    type: "JSON",
    notnull,
    primary,
  };
}
