// 插件侧展示账本：宿主仅支持 append/delta/clear，撤回失败 attempt 时重放已提交消息。
import type { FollowMessage } from './sessionWire.js';

export class Conversation {
    private messages: FollowMessage[] = [];
    private readonly localRequests = new Set<string>();
    buffer = '';

    constructor(private readonly emit: (event: unknown) => void) {}

    snapshot(messages: FollowMessage[]): void {
        this.messages = messages.slice(-500);
        this.buffer = '';
        this.replay();
    }

    submitted(requestId: string, text: string): void {
        // submit 已由宿主乐观追加；这里只保留重放与 rpcId 关联所需的记录。
        this.localRequests.add(requestId);
        if (this.localRequests.size > 500) {
            const oldest = this.localRequests.values().next().value;
            if (oldest !== undefined) this.localRequests.delete(oldest);
        }
        this.remember({ role: 'user', text });
    }

    message(message: FollowMessage): void {
        if (message.role === 'user' && message.rpcId !== undefined && this.localRequests.has(message.rpcId)) return;
        this.remember(message);
        if (message.role === 'assistant' && this.buffer.length > 0) {
            // 落库正文是权威结果，也结束宿主的 pending assistant 气泡。
            this.buffer = '';
            this.replay();
        } else {
            this.emit({ type: 'message', role: message.role, text: message.text });
        }
    }

    delta(text: string): void {
        this.buffer += text;
        if (text.length > 0) this.emit({ type: 'delta', text });
    }

    resetAttempt(): void {
        if (this.buffer.length === 0) return;
        this.buffer = '';
        this.replay();
    }

    private remember(message: FollowMessage): void {
        this.messages.push(message);
        if (this.messages.length > 500) this.messages.shift();
    }

    private replay(): void {
        this.emit({ type: 'clear' });
        for (const message of this.messages) {
            this.emit({ type: 'message', role: message.role, text: message.text });
        }
    }
}
