const copyright = {
  template: `  
   <div class="Mod-copyright">
    <ul class="helpLink">
      <li>关于星购<span class="space"></span></li>
      <li>联系我们<span class="space"></span></li>
      <li>商家入驻<span class="space"></span></li>
      <li>营销中心<span class="space"></span></li>
      <li>友情链接<span class="space"></span></li>
      <li>隐私政策</li>
    </ul>
    <p>地址：星购商城运营中心 邮编：100000 电话：400-668-8899  网址：http://www.starmall.com </p>
    <p>ICP备xxxxxxxx号 星购商城版权所有 ©2026 StarMall</p>
</div>  
    `,
  name: "copyright",
  data() {
    return {}
  }
};
Vue.component("copyright", copyright);