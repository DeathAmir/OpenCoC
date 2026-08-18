#ifndef OPENCOC_GHIDRA_COMPAT_H
#define OPENCOC_GHIDRA_COMPAT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifndef __cplusplus
#include <stdalign.h>
#endif

typedef uint8_t byte;
typedef int8_t sbyte;
typedef uint16_t ushort;
typedef uint32_t uint;
typedef unsigned long ulong;
typedef int64_t longlong;
typedef uint64_t ulonglong;

typedef uint8_t undefined;
typedef uint8_t undefined1;
typedef uint16_t undefined2;
typedef uint32_t undefined4;
typedef uint64_t undefined8;
typedef void code;

#endif
