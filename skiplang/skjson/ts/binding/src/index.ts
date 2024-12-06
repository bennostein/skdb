import * as Internal from "./internal.js";
import { Type, type Binding } from "./binding.js";
import type { Pointer, Nullable } from "@skiplang/std";
export type { Pointer, Nullable, Binding };
export type { Type };

export const sk_isObjectProxy: unique symbol = Symbol();
export const sk_frozen: unique symbol = Symbol.for("Skip.frozen");

export type Constant = { [sk_frozen]: true };

export function sk_freeze<T extends object>(x: T): T & Constant {
  return Object.defineProperty(x, sk_frozen, {
    enumerable: false,
    writable: false,
    value: true,
  }) as T & Constant;
}

export function isSkFrozen(x: any): x is Constant {
  return sk_frozen in x && x[sk_frozen] === true;
}

/**
 * The `Json` type describes JSON-serializable values and serves as an upper bound on keys
 * and values in the Skip Runtime, ensuring that they can be serialized and managed by the
 * reactive computation engine.
 */
export type Json = number | boolean | string | (Json | null)[] | JsonObject;
export type JsonObject = { [key: string]: Json | null };

export type Exportable =
  | Json
  | null
  | undefined
  | ObjectProxy<{ [k: string]: Exportable }>
  | (readonly Exportable[] & Constant);

export type ObjectProxy<Base extends { [k: string]: Exportable }> = {
  [sk_isObjectProxy]: true;
  [sk_frozen]: true;
  __pointer: Pointer<Internal.CJSON>;
  clone: () => ObjectProxy<Base>;
  toJSON: () => Base;
  keys: IterableIterator<keyof Base>;
} & Base;

export function isObjectProxy(
  x: any,
): x is ObjectProxy<{ [k: string]: Exportable }> {
  return sk_isObjectProxy in x && (x[sk_isObjectProxy] as boolean);
}

export const reactiveObject = {
  get<Base extends { [k: string]: Exportable }>(
    hdl: ObjectHandle<Internal.CJObject>,
    prop: string | symbol,
    self: ObjectProxy<Base>,
  ): any {
    if (prop === sk_isObjectProxy) return true;
    if (prop === sk_frozen) return true;
    if (prop === "__pointer") return hdl.pointer;
    if (prop === "clone") return (): ObjectProxy<Base> => clone(self);
    if (typeof prop === "symbol") return undefined;
    const fields = hdl.objectFields();
    if (prop === "toJSON")
      return (): Base => {
        return Object.fromEntries(
          Array.from(fields).map(([k, ptr]) => [k, getFieldAt(hdl, ptr)]),
        ) as Base;
      };
    if (prop === "keys") return fields.keys();
    if (prop === "toString") return () => JSON.stringify(self);
    const idx = fields.get(prop);
    if (idx === undefined) return undefined;
    return getFieldAt(hdl, idx);
  },
  set(
    _hdl: ObjectHandle<Internal.CJObject>,
    _prop: string | symbol,
    _value: any,
  ) {
    throw new Error("Reactive object cannot be modified.");
  },
  has(hdl: ObjectHandle<Internal.CJObject>, prop: string | symbol): boolean {
    if (prop === sk_isObjectProxy) return true;
    if (prop === sk_frozen) return true;
    if (prop === "__pointer") return true;
    if (prop === "clone") return true;
    if (prop === "keys") return true;
    if (prop === "toJSON") return true;
    if (prop === "toString") return true;
    if (typeof prop === "symbol") return false;
    const fields = hdl.objectFields();
    return fields.has(prop);
  },
  ownKeys(hdl: ObjectHandle<Internal.CJObject>) {
    return Array.from(hdl.objectFields().keys());
  },
  getOwnPropertyDescriptor(
    hdl: ObjectHandle<Internal.CJObject>,
    prop: string | symbol,
  ) {
    if (typeof prop === "symbol") return undefined;
    const fields = hdl.objectFields();
    const idx = fields.get(prop);
    if (idx === undefined) return undefined;
    const value = getFieldAt(hdl, idx);
    return {
      configurable: true,
      enumerable: true,
      writable: false,
      value,
    };
  },
};

export function clone<T>(value: T): T {
  if (value !== null && typeof value === "object") {
    if (Array.isArray(value)) {
      return value.map(clone) as T;
    } else if (isObjectProxy(value)) {
      return Object.fromEntries(
        Array.from(value.keys).map((k) => [k, clone(value[k])]),
      ) as T;
    } else {
      return Object.fromEntries(
        Object.entries(value).map(([k, v]): [string, any] => [k, clone(v)]),
      ) as T;
    }
  } else {
    return value;
  }
}

function interpretPointer<T extends Internal.CJSON>(
  hdl: ObjectHandle<any>,
  pointer: Nullable<Pointer<T>>,
): Exportable {
  if (pointer === null) return null;
  const type = hdl.binding.SKIP_SKJSON_typeOf(pointer);
  switch (type) {
    case Type.Null:
      return null;
    case Type.Int:
    case Type.Float:
      return hdl.binding.SKIP_SKJSON_asNumber(pointer);
    case Type.Boolean:
      return hdl.binding.SKIP_SKJSON_asBoolean(pointer);
    case Type.String:
      return hdl.binding.SKIP_SKJSON_asString(pointer);
    case Type.Array: {
      const aPtr = hdl.binding.SKIP_SKJSON_asArray(pointer);
      const length = hdl.binding.SKIP_SKJSON_arraySize(aPtr);
      const array = Array.from({ length }, (_, idx) =>
        interpretPointer(hdl, hdl.binding.SKIP_SKJSON_at(aPtr, idx)),
      );
      return sk_freeze(array);
    }
    case Type.Object: {
      const oPtr = hdl.binding.SKIP_SKJSON_asObject(pointer);
      return new Proxy(
        hdl.derive(oPtr),
        reactiveObject,
      ) as unknown as ObjectProxy<{ [k: string]: Exportable }>;
    }
    case Type.Undefined:
    default:
      return undefined;
  }
}

function getFieldAt<T extends Internal.CJObject>(
  hdl: ObjectHandle<T>,
  idx: number,
): Exportable {
  return interpretPointer(hdl, hdl.binding.SKIP_SKJSON_get(hdl.pointer, idx));
}

class ObjectHandle<T extends Internal.CJSON> {
  binding: Binding;
  pointer: Pointer<T>;
  fields?: Map<string, number>;

  constructor(binding: Binding, pointer: Pointer<T>) {
    this.pointer = pointer;
    this.binding = binding;
  }

  objectFields(this: ObjectHandle<Internal.CJObject>) {
    if (!this.fields) {
      this.fields = new Map();
      const size = this.binding.SKIP_SKJSON_objectSize(this.pointer);
      for (let i = 0; i < size; i++) {
        const field = this.binding.SKIP_SKJSON_fieldAt(this.pointer, i);
        if (!field) break;
        this.fields.set(field, i);
      }
    }
    return this.fields;
  }

  derive<U extends Internal.CJSON>(pointer: Pointer<U>) {
    return new ObjectHandle(this.binding, pointer);
  }
}

export function exportJSON(
  binding: Binding,
  value: Exportable,
): Pointer<Internal.CJSON> {
  if (value === null || value === undefined) {
    return binding.SKIP_SKJSON_createCJNull();
  } else if (typeof value == "number") {
    if (value === Math.trunc(value)) {
      return binding.SKIP_SKJSON_createCJInt(value);
    } else {
      return binding.SKIP_SKJSON_createCJFloat(value);
    }
  } else if (typeof value == "boolean") {
    return binding.SKIP_SKJSON_createCJBool(value);
  } else if (typeof value == "string") {
    return binding.SKIP_SKJSON_createCJString(value);
  } else if (Array.isArray(value)) {
    const arr = binding.SKIP_SKJSON_startCJArray();
    value.forEach((v) => {
      binding.SKIP_SKJSON_addToCJArray(arr, exportJSON(binding, v));
    });
    return binding.SKIP_SKJSON_endCJArray(arr);
  } else if (typeof value == "object") {
    if (isObjectProxy(value)) {
      return value.__pointer;
    } else {
      const obj = binding.SKIP_SKJSON_startCJObject();
      Object.entries(value).forEach(([key, val]) => {
        binding.SKIP_SKJSON_addToCJObject(obj, key, exportJSON(binding, val));
      });
      return binding.SKIP_SKJSON_endCJObject(obj);
    }
  } else {
    throw new Error(`'${typeof value}' cannot be exported to wasm.`);
  }
}

export function importJSON<T extends Internal.CJSON>(
  binding: Binding,
  pointer: Pointer<T>,
  copy?: boolean,
): Exportable {
  const value = interpretPointer(new ObjectHandle(binding, pointer), pointer);
  return copy && value !== null ? clone(value) : value;
}

export interface JsonConverter {
  importJSON(value: Pointer<Internal.CJSON>, copy?: boolean): Exportable;
  exportJSON(v: null | undefined): Pointer<Internal.CJNull>;
  exportJSON(v: number): Pointer<Internal.CJFloat | Internal.CJInt>;
  exportJSON(v: boolean): Pointer<Internal.CJBool>;
  exportJSON(v: string): Pointer<Internal.CJString>;
  exportJSON(v: any[]): Pointer<Internal.CJArray>;
  exportJSON(v: JsonObject): Pointer<Internal.CJObject>;
  exportJSON<T extends Internal.CJSON>(
    v: ObjectProxy<{ [k: string]: Exportable }> & {
      __pointer: Pointer<T>;
    },
  ): Pointer<T>;
  exportJSON(v: Nullable<Json>): Pointer<Internal.CJSON>;
  importOptJSON(
    value: Nullable<Pointer<Internal.CJSON>>,
    copy?: boolean,
  ): Exportable;
  is(v: Pointer<Internal.CJSON>, type: Type): boolean;
  clone<T>(v: T): T;
}

export class JsonConverterImpl implements JsonConverter {
  constructor(private binding: Binding) {}

  importJSON(value: Pointer<Internal.CJSON>, copy?: boolean): Exportable {
    return importJSON(this.binding, value, copy);
  }

  exportJSON(v: Exportable): Pointer<Internal.CJSON> {
    return exportJSON(this.binding, v);
  }

  public clone<T>(v: T): T {
    return clone(v);
  }

  public is(v: Pointer<Internal.CJSON>, type: Type): boolean {
    return this.binding.SKIP_SKJSON_typeOf(v) == type;
  }

  importOptJSON(
    value: Nullable<Pointer<Internal.CJSON>>,
    copy?: boolean,
  ): Exportable {
    if (value === null) {
      return null;
    }
    return this.importJSON(value, copy);
  }
}

export function buildJsonConverter(binding: Binding): JsonConverter {
  return new JsonConverterImpl(binding);
}
