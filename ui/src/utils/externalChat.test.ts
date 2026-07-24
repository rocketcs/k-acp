import assert from 'node:assert/strict'
import test from 'node:test'
import { buildExternalChatUrl, copyText, type ClipboardEnvironment } from './externalChat.ts'

test('外置对话链接保留部署目录和端口', () => {
  assert.equal(
    buildExternalChatUrl('http://192.168.8.81:23080', '/web', 'chat-key'),
    'http://192.168.8.81:23080/web/#/communication/chat-key',
  )
})

test('Clipboard API 不可用时使用传统复制方式', async () => {
  let copiedText = ''
  let selected = false

  const environment = {
    navigator: {
      clipboard: {
        writeText: async () => {
          throw new Error('Clipboard API unavailable')
        },
      },
    },
    document: {
      body: {
        appendChild: () => undefined,
        removeChild: () => undefined,
      },
      createElement: () => ({
        value: '',
        style: {},
        setAttribute: () => undefined,
        select: () => { selected = true },
      }),
      execCommand: (command: string) => {
        copiedText = command
        return true
      },
    },
  } as unknown as ClipboardEnvironment

  await copyText('external-chat-url', environment)

  assert.equal(copiedText, 'copy')
  assert.equal(selected, true)
})
