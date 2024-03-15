#include "runtime.h"

#ifdef SKIP64
#include <unistd.h>
#endif

/*****************************************************************************/
/* Operations on the runtime representation of skip values. */
/*****************************************************************************/

SKIP_gc_type_t* get_gc_type(char* skip_object) {
  // a vtable pointer immediately precedes a pointer to each skip object
  SKIP_gc_type_t*** vtable = ((SKIP_gc_type_t***)skip_object) - 1;
  // the gc_type of each object is stored in slot 1 of the vtable,
  // see createVTableBuilders in vtable.sk
  SKIP_gc_type_t** slot1 = *(vtable) + 1;
  return *slot1;
}

/*****************************************************************************/
/* Primitives that are not used in embedded mode. */
/*****************************************************************************/

void SKIP_Regex_initialize() {}

void SKIP_print_last_exception_stack_trace_and_exit(void*) {
  todo();
}
void SKIP_unreachableMethodCall(void*, void*) {
  todo();
}
void SKIP_unreachableWithExplanation(void*) {
  todo();
}

void SKIP_Obstack_vectorUnsafeSet(char** arr, char* x) {
  *arr = x;
}

void SKIP_Obstack_collect(char*, char**, SkipInt) {}

void* SKIP_llvm_memcpy(char* dest, char* val, SkipInt len) {
  return memcpy(dest, val, (size_t)len);
}

/*****************************************************************************/
/* Global context synchronization. */
/*****************************************************************************/

void SKIP_context_init(char* obj) {
  sk_global_lock();
  char* context = SKIP_intern_shared(obj);
  sk_context_set_unsafe(context);
  sk_global_unlock();
}

void SKIP_unsafe_context_incr_ref_count(char* obj) {
  sk_incr_ref_count(obj);
}

void SKIP_unsafe_free(char* context) {
  sk_global_lock();
  sk_free_root(context);
  sk_global_unlock();
}

void SKIP_global_lock() {
#ifdef SKIP64
  sk_global_lock();
#endif
}

#ifdef SKIP64
extern int sk_is_locked;
#endif

uint32_t SKIP_global_has_lock() {
#ifdef SKIP64
  return (uint32_t)sk_is_locked;
#endif
#ifdef SKIP32
  return 1;
#endif
}

void SKIP_global_unlock() {
#ifdef SKIP64
  sk_global_unlock();
#endif
}

void* SKIP_context_sync_no_lock(uint64_t txTime, char* old_root, char* delta,
                                char* synchronizer, uint32_t sync,
                                char* lockF) {
  char* root = SKIP_context_get_unsafe();
  if (root == NULL) {
#ifdef SKIP64
    fprintf(stderr, "Internal error: you forgot to initialize the context");
#endif
    SKIP_throw_cruntime(ERROR_CONTEXT_NOT_INITIALIZED);
  }
  if (root == delta || old_root == delta) {
// INVALID use of sync, the root should be different
#ifdef SKIP64
    fprintf(stderr, "Internal error: tried to sync with the same context");
#endif
    SKIP_throw_cruntime(ERROR_SYNC_SAME_CONTEXT);
  }
  char* rtmp = SKIP_resolve_context(txTime, root, delta, synchronizer, lockF);
  char* new_root = SKIP_intern_shared(rtmp);
  sk_commit(new_root, sync);
  sk_free_root(old_root);
  sk_free_root(root);
  sk_free_root(root);
  sk_free_external_pointers();
#ifdef CTX_TABLE
  sk_print_ctx_table();
#endif
  sk_incr_ref_count(new_root);
  return new_root;
}

void* SKIP_context_sync(uint64_t txTime, char* old_root, char* delta,
                        char* synchronizer, uint32_t sync, char* lockF) {
  sk_global_lock();
  char* new_root = SKIP_context_sync_no_lock(txTime, old_root, delta,
                                             synchronizer, sync, lockF);
  sk_global_unlock();
  SKIP_call_after_unlock(synchronizer, delta);
  return new_root;
}
