/**
 * Minimal in-memory stand-in for the drizzle `db` client, for route unit
 * tests (installed via `__setDbForTests` from '@/db'). Every query chain is
 * thenable; when awaited it calls `respond` with what was asked, so a test
 * answers by table / SQL text without a database. Test-only — never imported
 * by application code.
 */
import { getTableName, isTable, SQL } from 'drizzle-orm'
import type { Table } from 'drizzle-orm'
import { PgDialect } from 'drizzle-orm/pg-core'

export type FakeOp = 'select' | 'insert' | 'update' | 'delete' | 'execute'

export interface FakeQuery {
  op: FakeOp
  /** Table named by from()/insert()/update()/delete(), when there is one. */
  table: string | null
  /** Rendered SQL for db.execute(sql`…`). */
  sqlText: string | null
  /** Chain calls after the root, e.g. [['set', [{…}]], ['where', [...]]]. */
  calls: Array<[string, unknown[]]>
}

const dialect = new PgDialect()

function tableName(value: unknown): string | null {
  return isTable(value) ? getTableName(value as Table) : null
}

export function createFakeDb(respond: (q: FakeQuery) => unknown) {
  const log: FakeQuery[] = []

  function chain(q: FakeQuery): unknown {
    log.push(q)
    const proxy: unknown = new Proxy(
      {},
      {
        get(_t, prop) {
          if (prop === 'then') {
            const p = Promise.resolve().then(() => respond(q))
            return p.then.bind(p)
          }
          return (...args: unknown[]) => {
            if (prop === 'from' && q.table === null) q.table = tableName(args[0])
            q.calls.push([String(prop), args])
            return proxy
          }
        },
      },
    )
    return proxy
  }

  const root = (op: FakeOp) => (...args: unknown[]) =>
    chain({ op, table: tableName(args[0]), sqlText: null, calls: [] })

  const db = {
    select: root('select'),
    insert: root('insert'),
    update: root('update'),
    delete: root('delete'),
    execute: (query: unknown) => {
      const sqlText = query instanceof SQL ? dialect.sqlToQuery(query).sql : String(query)
      return chain({ op: 'execute', table: null, sqlText, calls: [] })
    },
  }
  return { db, log }
}

/** First argument passed to a chain call, e.g. the object given to `.set()`. */
export function callArg(q: FakeQuery, name: string): unknown {
  return q.calls.find(([n]) => n === name)?.[1][0]
}
