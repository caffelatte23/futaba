import { readFileSync } from 'node:fs'
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing'
import { doc, getDoc, setDoc } from 'firebase/firestore'
import { afterAll, beforeAll, beforeEach, expect, test } from 'vitest'

const notePath = 'users/alice/notes/n1'
const labelPath = 'users/alice/labels/l1'
const note = { text: 'An idea' }
const label = { name: 'Ideas' }

let testEnv: RulesTestEnvironment

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'demo-futaba',
    firestore: { rules: readFileSync('firestore.rules', 'utf8') },
  })
})

beforeEach(async () => {
  await testEnv.clearFirestore()
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore()
    await setDoc(doc(db, notePath), note)
    await setDoc(doc(db, labelPath), label)
  })
})

afterAll(async () => {
  await testEnv?.cleanup()
})

test('owner can read and write own notes', async () => {
  const ref = doc(testEnv.authenticatedContext('alice').firestore(), notePath)
  const updatedNote = { text: 'An updated idea' }

  await assertSucceeds(setDoc(ref, updatedNote))
  expect((await assertSucceeds(getDoc(ref))).data()).toEqual(updatedNote)
})

test('other user is denied', async () => {
  const ref = doc(testEnv.authenticatedContext('bob').firestore(), notePath)

  await assertFails(getDoc(ref))
  await assertFails(setDoc(ref, { text: 'A different idea' }))
})

test('unauthenticated is denied', async () => {
  const ref = doc(testEnv.unauthenticatedContext().firestore(), notePath)

  await assertFails(getDoc(ref))
})

test('labels follow the same rule', async () => {
  const ownerRef = doc(testEnv.authenticatedContext('alice').firestore(), labelPath)
  const otherRef = doc(testEnv.authenticatedContext('bob').firestore(), labelPath)
  const unauthenticatedRef = doc(testEnv.unauthenticatedContext().firestore(), labelPath)
  const updatedLabel = { name: 'Notebook' }

  await assertSucceeds(setDoc(ownerRef, updatedLabel))
  expect((await assertSucceeds(getDoc(ownerRef))).data()).toEqual(updatedLabel)
  await assertFails(getDoc(otherRef))
  await assertFails(setDoc(otherRef, { name: 'Other label' }))
  await assertFails(getDoc(unauthenticatedRef))
})
