/**
 * AiChat —— 星购商城全局悬浮 AI 智能助手组件
 * ------------------------------------------------------------------
 * 设计说明：
 *  1. 本文件是「独立、解耦」的组件文件，不依赖首页任何业务代码，
 *     后续接入 Spring AI 后端只需实现 POST /ai/chat 接口即可。
 *  2. 门户工程为静态 HTML + Vue 2 全局构建（js/vue.js），无打包工具，
 *     故以 Vue.component 全局注册的方式封装（等价于 .vue 单文件组件）。
 *  3. 组件通过 fetch 以 SSE 流式方式与后端交互（axios 不支持流式读取响应体）：
 *     common.js 中 axios.defaults.baseURL = "/api"，
 *     故这里请求 POST /api/ai/chat/stream，请求体：{ "content": "用户消息" }，
 *     响应为 text/event-stream，逐段 data: 文本追加渲染，实现打字机效果。
 */
const aiChatApp = {
  template: `
  <div class="ai-chat">
    <!-- 悬浮按钮：右下角固定，圆形，星空蓝主色 -->
    <button v-show="!visible" class="ai-chat-fab" type="button" title="星购 AI 智能助手" @click="open">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
           stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 3.2V5.4"/>
        <circle cx="12" cy="2.6" r="0.9" fill="currentColor" stroke="none"/>
        <rect x="4.2" y="5.4" width="15.6" height="12.4" rx="4.2"/>
        <circle cx="9.2" cy="11.2" r="1.4" fill="currentColor" stroke="none"/>
        <circle cx="14.8" cy="11.2" r="1.4" fill="currentColor" stroke="none"/>
        <path d="M9.4 15.2h5.2"/>
        <path d="M4.2 11.6H2.2M19.8 11.6h2"/>
      </svg>
    </button>

    <!-- 遮罩层 -->
    <transition name="ai-chat-mask-fade">
      <div v-show="visible" class="ai-chat-mask" @click="close"></div>
    </transition>

    <!-- 右侧滑出对话抽屉 -->
    <div class="ai-chat-drawer" :class="{ 'is-open': visible }">
      <!-- 头部 -->
      <div class="ai-chat-header">
        <div class="ai-chat-header-left">
          <div class="ai-chat-header-avatar">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3.2V5.4"/>
              <rect x="4.2" y="5.4" width="15.6" height="12.4" rx="4.2"/>
              <circle cx="9.2" cy="11.2" r="1.4" fill="currentColor" stroke="none"/>
              <circle cx="14.8" cy="11.2" r="1.4" fill="currentColor" stroke="none"/>
              <path d="M9.4 15.2h5.2"/>
            </svg>
          </div>
          <div>
            <div class="ai-chat-title">星购 AI 智能助手</div>
            <div class="ai-chat-subtitle">智能导购 · 随时在线</div>
          </div>
        </div>
        <button class="ai-chat-close" type="button" title="关闭" @click="close">×</button>
      </div>

      <!-- 消息列表区 -->
      <div class="ai-chat-body" ref="body">
        <div v-for="(msg, index) in messages" :key="index"
             class="ai-chat-row" :class="msg.role === 'user' ? 'is-user' : 'is-ai'">
          <div class="ai-chat-avatar">
            <svg v-if="msg.role === 'ai'" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3.2V5.4"/>
              <rect x="4.2" y="5.4" width="15.6" height="12.4" rx="4.2"/>
              <circle cx="9.2" cy="11.2" r="1.4" fill="currentColor" stroke="none"/>
              <circle cx="14.8" cy="11.2" r="1.4" fill="currentColor" stroke="none"/>
              <path d="M9.4 15.2h5.2"/>
            </svg>
            <template v-else>我</template>
          </div>
          <div class="ai-chat-bubble">{{ msg.content }}</div>
        </div>

        <!-- 等待后端返回时的状态 -->
        <div v-if="loading" class="ai-chat-row is-ai">
          <div class="ai-chat-avatar">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3.2V5.4"/>
              <rect x="4.2" y="5.4" width="15.6" height="12.4" rx="4.2"/>
              <circle cx="9.2" cy="11.2" r="1.4" fill="currentColor" stroke="none"/>
              <circle cx="14.8" cy="11.2" r="1.4" fill="currentColor" stroke="none"/>
              <path d="M9.4 15.2h5.2"/>
            </svg>
          </div>
          <div class="ai-chat-bubble ai-chat-typing">
            <span></span><span></span><span></span>
          </div>
        </div>
      </div>

      <!-- 底部输入区 -->
      <div class="ai-chat-footer">
        <input class="ai-chat-input" v-model="input" :disabled="loading"
               placeholder="请输入你的问题，回车发送" @keyup.enter="send"/>
        <button class="ai-chat-send" type="button" :disabled="loading || !input.trim()" @click="send">
          发送
        </button>
      </div>
    </div>
  </div>
  `,
  data() {
    return {
      visible: false,        // 抽屉是否展开
      input: "",             // 输入框内容
      loading: false,        // 是否等待后端响应
      chatId: "",            // 当前会话 id（恢复历史后携带，保证连续对话）
      historyLoaded: false,  // 是否已尝试恢复过历史记录（每次页面只拉一次）
      messages: [
        { role: "ai", content: "您好，我是星购 AI 智能助手，有什么可以帮您？" }
      ]
    };
  },
  methods: {
    // 打开抽屉
    open() {
      this.visible = true;
      this.loadHistory();
      this.$nextTick(this.scrollToBottom);
    },
    // 首次打开时恢复最近一次会话的聊天记录（后端记忆窗口只保留最近 10 条）
    loadHistory() {
      if (this.historyLoaded || this.messages.length > 1) {
        return;
      }
      this.historyLoaded = true;
      axios.get("/ai/sessions").then(sessions => {
        if (!Array.isArray(sessions) || sessions.length === 0) {
          return;
        }
        this.chatId = sessions[0].chatId;
        return axios.get("/ai/sessions/" + encodeURIComponent(this.chatId) + "/messages").then(list => {
          if (!Array.isArray(list)) {
            return;
          }
          // 只恢复用户与 AI 的对话消息，跳过工具/系统消息与空内容
          const restored = list
            .filter(m => (m.role === "USER" || m.role === "ASSISTANT") && m.content)
            .map(m => ({ role: m.role === "USER" ? "user" : "ai", content: m.content }));
          if (restored.length > 0) {
            this.messages = restored;
            this.$nextTick(this.scrollToBottom);
          }
        });
      }).catch(err => {
        console.debug && console.debug(err);
      });
    },
    // 关闭抽屉
    close() {
      this.visible = false;
    },
    // 消息列表滚动到底部
    scrollToBottom() {
      const body = this.$refs.body;
      if (body) {
        body.scrollTop = body.scrollHeight;
      }
    },
    // 发送消息（流式：fetch + ReadableStream 逐段接收，打字机效果，首字秒出）
    async send() {
      const content = this.input.trim();
      if (!content || this.loading) {
        return;
      }
      // 追加用户消息
      this.messages.push({ role: "user", content: content });
      this.input = "";
      this.loading = true;
      this.$nextTick(this.scrollToBottom);

      // AI 回复气泡先占位，收到第一段再插入，之后逐段追加内容
      const reply = { role: "ai", content: "" };
      let inserted = false;
      try {
        // fetch 绕过 axios（axios 不支持流式读取响应体），路径与 token 头和 common.js 保持一致
        const res = await fetch("/api/ai/chat/stream", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "authorization": sessionStorage.getItem("token")
          },
          body: JSON.stringify({ content: content, chatId: this.chatId || undefined })
        });
        if (res.status === 401) {
          // 认证失败，与 common.js 拦截器行为对齐
          sessionStorage.removeItem("user-info");
          sessionStorage.removeItem("token");
          location.href = "/login.html";
          return;
        }
        if (!res.ok || !res.body) {
          throw new Error("HTTP " + res.status);
        }

        // SSE 事件以空行分隔，逐段解析后追加到气泡
        const reader = res.body.getReader();
        const decoder = new TextDecoder("utf-8");
        let buffer = "";
        while (true) {
          const chunk = await reader.read();
          if (chunk.done) {
            break;
          }
          buffer += decoder.decode(chunk.value, { stream: true });
          let idx;
          while ((idx = buffer.indexOf("\n\n")) >= 0) {
            const text = this.parseSseEvent(buffer.slice(0, idx));
            buffer = buffer.slice(idx + 2);
            if (text) {
              if (!inserted) {
                this.messages.push(reply);
                inserted = true;
              }
              reply.content += text;
              this.$nextTick(this.scrollToBottom);
            }
          }
        }
        if (!inserted) {
          this.messages.push({ role: "ai", content: "AI 助手没有返回内容，请稍后再试。" });
        }
      } catch (err) {
        console.debug && console.debug(err);
        if (!inserted) {
          this.messages.push({ role: "ai", content: "抱歉，AI 助手暂时无法连接，请稍后再试。" });
        }
      } finally {
        this.loading = false;
        this.$nextTick(this.scrollToBottom);
      }
    },
    // 解析一段 SSE 事件文本：合并多行 data: 为完整字符串（后端 Flux<String> 按 SSE 规范编码）
    parseSseEvent(raw) {
      const dataLines = [];
      raw.split("\n").forEach(line => {
        if (line.startsWith("data:")) {
          dataLines.push(line.slice(5).replace(/^ /, ""));
        }
      });
      return dataLines.join("\n");
    }
  }
};

Vue.component("ai-chat", aiChatApp);
