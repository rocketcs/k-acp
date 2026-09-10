import type { ChatMessageVO, Message } from '@/types'

export type RuntimeChatMessage = Pick<ChatMessageVO, 'id' | 'role' | 'content'>

export function createRuntimeUserMessage(
  persistedUserMessage: Pick<ChatMessageVO, 'id'>,
  runtimeText: string,
): RuntimeChatMessage {
  return {
    id: persistedUserMessage.id,
    role: 'user',
    content: runtimeText,
  }
}

export function toAguiRuntimeMessages(messages: RuntimeChatMessage[]): Message[] {
  return messages
    .filter((message) => !['system', 'tool'].includes(message.role))
    .map((message) => ({
      id: String(message.id),
      role: message.role as Message['role'],
      content: message.content || '',
    }))
}
