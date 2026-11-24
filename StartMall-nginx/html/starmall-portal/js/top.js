const topApp = {
  template:`
  <div class="top">
    <div class="py-container">
      <div class="shortcut">
        <ul class="fl">
          <li class="f-item" v-if="user">
            <span class="top-hello">欢迎您</span>
            <span class="top-username">{{user.username}}</span>
            <button type="button" class="top-logout" @click="util.logout()">退出登录</button>
          </li>
          <li class="f-item" v-else>
            <a href="/login.html" class="top-login">请登录</a>
            <a href="/register.html" class="top-register">免费注册</a>
          </li>
        </ul>
        <ul class="fr top-menu">
          <li class="f-item" v-for="item in menus" :key="item.name" :class="{active: item.active}">
            <a :href="item.href">{{item.name}}</a>
          </li>
        </ul>
      </div>
    </div>
  </div>
  `,
  data(){
    return {
      user: null,
      currentPath: '',
      util
    }
  },
  computed:{
    menus(){
      const path = this.currentPath
      const isIndex = path === '/' || path === '/index.html'
      const isCart = path === '/cart.html'
      const isOrders = path === '/orders.html'
      const isFocus = path === '/focus.html'
      return [
        {name: '首页', href: '/', active: isIndex},
        {name: '我的购物车', href: '/cart.html', active: isCart},
        {name: '我的订单', href: '/orders.html', active: isOrders},
        {name: '我的关注', href: '/focus.html', active: isFocus},
        {name: '我的星购', href: 'javascript:void(0)', active: false},
        {name: '星购会员', href: 'javascript:void(0)', active: false},
        {name: '企业采购', href: 'javascript:void(0)', active: false},
        {name: '关注星购', href: 'javascript:void(0)', active: false},
        {name: '客户服务', href: 'javascript:void(0)', active: false},
        {name: '网站导航', href: 'javascript:void(0)', active: false}
      ]
    }
  },
  mounted(){
    this.user = this.util.store.get("user-info")
    this.currentPath = window.location.pathname
  }
}

Vue.component("top", topApp);
