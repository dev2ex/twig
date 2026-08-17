package com.twig.app.share

/**
 * 页面的样式与脚本。单独一个文件是因为它们是**资源**不是逻辑——夹在渲染函数中间会把
 * [WebUi] 那点真正的结构淹掉。
 *
 * 两条自我约束:
 *  - **不引任何外部资源**。对端多半只连着这台手机的热点或一个没有外网的局域网,
 *    一个 CDN 引用就是一张白页。图标全是内联 SVG symbol,字体用系统栈。
 *  - **JS 写 ES5**(var / function,不用箭头函数与模板字符串)。这页面是对端访问
 *    文件的唯一入口,为一点语法糖在老浏览器上整页报废不值当。
 */
internal object WebPage {

    /**
     * 图标。用一组 `<symbol>` 定义、`<use>` 引用:同一个图标在页面里出现几十次也只有
     * 一份路径数据,比每行内联一段 SVG 小得多,也比 emoji 稳(emoji 在各平台上大小、
     * 基线、配色都不一样,行高会被撑得参差不齐——这正是上一版看着"原始"的原因之一)。
     */
    val SPRITE = """
        <svg xmlns="http://www.w3.org/2000/svg" style="display:none">
          <symbol id="i-folder" viewBox="0 0 24 24">
            <path d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z"/>
          </symbol>
          <symbol id="i-file" viewBox="0 0 24 24">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm-1 7V3.5L18.5 9H13z"/>
          </symbol>
          <symbol id="i-image" viewBox="0 0 24 24">
            <path d="M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 16H5l3.5-4.5
              2.5 3L14.5 12l4.5 7z"/>
          </symbol>
          <symbol id="i-video" viewBox="0 0 24 24">
            <path d="M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11l-4 4z"/>
          </symbol>
          <symbol id="i-audio" viewBox="0 0 24 24">
            <path d="M12 3v10.6A4 4 0 1 0 14 17V7h4V3h-6z"/>
          </symbol>
          <symbol id="i-archive" viewBox="0 0 24 24">
            <path d="M20 4H4a1 1 0 0 0-1 1v3a1 1 0 0 0 1 1h16a1 1 0 0 0 1-1V5a1 1 0 0 0-1-1zM5 11v8a1 1 0 0 0
              1 1h12a1 1 0 0 0 1-1v-8H5zm5 2h4v2h-4v-2z"/>
          </symbol>
          <symbol id="i-app" viewBox="0 0 24 24">
            <path d="M12 2 3 7v10l9 5 9-5V7l-9-5zm0 2.3 6.5 3.6L12 11.5 5.5 7.9 12 4.3zM5 9.6l6 3.4v6.6l-6-3.3V9.6z"/>
          </symbol>
          <symbol id="i-doc" viewBox="0 0 24 24">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2z
              m-3-5V3.5L18.5 9H13z"/>
          </symbol>
          <symbol id="i-code" viewBox="0 0 24 24">
            <path d="M9.4 16.6 4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0 4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z"/>
          </symbol>
          <symbol id="i-up" viewBox="0 0 24 24">
            <path d="M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20v-2z"/>
          </symbol>
          <!-- ★ 这些图标是 fill 渲染的,路径必须围出**面积**。原来的下载图标把竖杆写成
               `M12 3v10.2`(一条零宽度线段),结果只画出了箭头尖,行尾看着像个孤零零的
               "˅"。画新图标时先确认每一笔都有宽度,别照搬 stroke 版本的路径。 -->
          <symbol id="i-dl" viewBox="0 0 24 24">
            <path d="M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z"/>
          </symbol>
          <symbol id="i-pen" viewBox="0 0 24 24">
            <path d="M3 17.2V21h3.8L18 9.8 14.2 6 3 17.2zM20.7 7.1a1 1 0 0 0 0-1.4l-2.4-2.4a1 1 0 0 0-1.4 0L15
              5.2 18.8 9l1.9-1.9z"/>
          </symbol>
          <symbol id="i-x" viewBox="0 0 24 24">
            <path d="M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z"/>
          </symbol>
          <symbol id="i-up-arrow" viewBox="0 0 24 24">
            <path d="M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5z"/>
          </symbol>
          <symbol id="i-plus" viewBox="0 0 24 24">
            <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/>
          </symbol>
          <symbol id="i-device" viewBox="0 0 24 24">
            <path d="M12 21 8.5 16.5h7L12 21zM1.4 8.6C4.3 5.8 8 4.2 12 4.2s7.7 1.6 10.6 4.4l-1.8 1.8C18.4 8.1
              15.3 6.8 12 6.8S5.6 8.1 3.2 10.4L1.4 8.6zM5 12.2c1.9-1.8 4.4-2.9 7-2.9s5.1 1.1 7 2.9l-1.8 1.8c-1.4
              -1.3-3.2-2.1-5.2-2.1s-3.8.8-5.2 2.1L5 12.2z"/>
          </symbol>
          <symbol id="i-home" viewBox="0 0 24 24">
            <path d="M12 3 2 12h3v8h6v-5h2v5h6v-8h3L12 3z"/>
          </symbol>
          <symbol id="i-lock" viewBox="0 0 24 24">
            <path d="M18 8h-1V6A5 5 0 0 0 7 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0
              0-2-2zM9 6a3 3 0 0 1 6 0v2H9V6zm3 12a2 2 0 1 1 0-4 2 2 0 0 1 0 4z"/>
          </symbol>
        </svg>
    """.trimIndent()

    val CSS = """
        :root{
          --bg:#f5f6f8; --card:#fff; --fg:#1b1f24; --muted:#6b7280; --line:#e8eaee;
          --accent:#2f6feb; --accent-soft:#eaf1ff; --danger:#d93636; --hover:#f4f6f9;
          --shadow:0 1px 2px rgba(16,24,40,.06),0 1px 3px rgba(16,24,40,.08);
          --c-folder:#f0b429; --c-image:#12a37a; --c-video:#7c5cf0; --c-audio:#e0658a;
          --c-archive:#c2763c; --c-app:#3aa76d; --c-doc:#4a86e8; --c-code:#8b6cef;
          --c-file:#9aa1ab;
          --danger-soft:#fdecec; --danger-line:#f3c9c9; --track:#dfe3ea;
          --focus:rgba(47,111,235,.18);
        }
        @media(prefers-color-scheme:dark){
          :root{
            --bg:#0f1216; --card:#171b21; --fg:#e6e8ec; --muted:#98a0ad; --line:#2b323d;
            --accent:#77a5f7; --accent-soft:#1b2331; --danger:#f2777a; --hover:#1d222a;
            --shadow:none;
            --c-folder:#e8b13c; --c-image:#3cc79c; --c-video:#9d86f5; --c-audio:#ef86a6;
            --c-archive:#d18a52; --c-app:#5bc48c; --c-doc:#6fa1f0; --c-code:#a48bf3;
            --c-file:#7b838f;
            --danger-soft:#2c1b1d; --danger-line:#57302f; --track:#2a313b;
            --focus:rgba(119,165,247,.22);
          }
        }
        *{box-sizing:border-box}
        /* ★ 必须带 !important:下面 button 定了 display:inline-flex,优先级比
           浏览器默认样式表里的 [hidden]{display:none} 高,不压回去的话
           `<button hidden>` 会一直显示(「删除所选」在没选中时也露在工具栏上) */
        [hidden]{display:none!important}
        html,body{margin:0;padding:0}
        body{background:var(--bg);color:var(--fg);
          font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,
            "Noto Sans CJK SC","PingFang SC","Microsoft YaHei",sans-serif;
          -webkit-font-smoothing:antialiased}
        a{color:inherit;text-decoration:none}
        svg.ic{width:20px;height:20px;fill:currentColor;display:block}

        /* 顶栏 */
        header{position:sticky;top:0;z-index:20;background:var(--card);
          border-bottom:1px solid var(--line)}
        .hd{max-width:1080px;margin:0 auto;padding:12px 20px;display:flex;
          align-items:center;gap:12px}
        .brand{display:flex;align-items:center;gap:9px;font-weight:600;font-size:16px;
          min-width:0}
        .brand svg{width:19px;height:19px;fill:var(--accent);flex:none}
        .brand span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .chip{margin-left:auto;display:inline-flex;align-items:center;gap:5px;flex:none;
          font-size:12px;padding:3px 10px;border-radius:999px;
          background:var(--hover);color:var(--muted)}
        .chip svg{width:13px;height:13px;fill:currentColor}
        .crumbs{max-width:1080px;margin:0 auto;padding:0 20px 11px;font-size:13px;
          color:var(--muted);display:flex;flex-wrap:wrap;align-items:center;gap:2px}
        .crumbs a{padding:2px 6px;border-radius:6px;display:inline-flex;
          align-items:center;gap:5px}
        .crumbs a svg{width:14px;height:14px;fill:currentColor}
        .crumbs a:hover{background:var(--hover);color:var(--accent)}
        .crumbs a:last-child{color:var(--fg);font-weight:500}
        .crumbs i{color:var(--line);font-style:normal;user-select:none}

        main{max-width:1080px;margin:0 auto;padding:18px 20px 48px}
        .card{background:var(--card);border:1px solid var(--line);border-radius:14px;
          box-shadow:var(--shadow);overflow:hidden}

        .err{display:flex;gap:9px;align-items:flex-start;margin-bottom:16px;padding:12px 14px;
          border-radius:12px;background:var(--danger-soft);color:var(--danger);
          font-size:13.5px;word-break:break-all;border:1px solid var(--danger-line)}

        /* 工具区 */
        .tools{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px}
        button{font:inherit;display:inline-flex;align-items:center;gap:6px;
          padding:7px 14px;border:1px solid var(--line);border-radius:9px;
          background:var(--card);color:var(--fg);cursor:pointer;
          transition:background .12s,border-color .12s}
        button:hover{background:var(--hover)}
        button svg{width:16px;height:16px;fill:currentColor}
        button.primary{background:var(--accent);border-color:var(--accent);color:#fff}
        button.primary:hover{filter:brightness(1.08)}
        button.danger{color:var(--danger);border-color:var(--danger-line)}
        #drop{border:1.5px dashed var(--line);border-radius:12px;padding:11px;
          text-align:center;color:var(--muted);font-size:12.5px;margin-bottom:14px;
          transition:border-color .15s,background .15s,color .15s}
        #drop.over{border-color:var(--accent);background:var(--accent-soft);color:var(--accent)}
        #up{display:none;margin-bottom:14px;padding:12px 14px;border-radius:12px;
          background:var(--accent-soft);border:1px solid var(--line)}
        #upname{font-size:13px;color:var(--fg);word-break:break-all;margin-bottom:7px}
        #bar{height:5px;border-radius:99px;background:var(--track);overflow:hidden}
        #fill{height:100%;width:0;background:var(--accent);border-radius:99px;transition:width .15s}

        /* 筛选条 */
        .bar2{display:flex;gap:10px;align-items:center;flex-wrap:wrap;
          padding:12px 14px;border-bottom:1px solid var(--line)}
        #filter{font:inherit;flex:1;min-width:150px;padding:7px 12px;border-radius:9px;
          border:1px solid var(--line);background:var(--bg);color:var(--fg)}
        #filter:focus{outline:none;border-color:var(--accent);box-shadow:0 0 0 3px var(--focus)}
        #stats{font-size:12.5px;color:var(--muted);white-space:nowrap}

        /* 列表 */
        table{width:100%;border-collapse:collapse}
        thead th{font-size:12px;font-weight:500;color:var(--muted);text-align:left;
          padding:9px 14px;border-bottom:1px solid var(--line);white-space:nowrap;
          background:var(--card)}
        th.s{cursor:pointer;user-select:none}
        th.s:hover{color:var(--accent)}
        th.s span{border-bottom:1px dashed transparent}
        th.s[data-d]{color:var(--accent)}
        th.s[data-d=a] span:after{content:"↑";margin-left:4px}
        th.s[data-d=d] span:after{content:"↓";margin-left:4px}
        th.ck,td.ck{width:38px;padding-right:0}
        th.sz,td.sz,th.dt,td.dt{text-align:right;width:1%;white-space:nowrap}
        tbody td{padding:0 14px;border-bottom:1px solid var(--line);height:46px;
          vertical-align:middle}
        tbody tr:last-child td{border-bottom:0}
        tbody tr:hover{background:var(--hover)}
        tbody tr.sel-on{background:var(--accent-soft)}
        td.ic{width:34px;padding-right:0}
        td.ic svg{color:var(--c-file)}
        tr[data-c=folder] td.ic svg{color:var(--c-folder)}
        tr[data-c=image] td.ic svg{color:var(--c-image)}
        tr[data-c=video] td.ic svg{color:var(--c-video)}
        tr[data-c=audio] td.ic svg{color:var(--c-audio)}
        tr[data-c=archive] td.ic svg{color:var(--c-archive)}
        tr[data-c=app] td.ic svg{color:var(--c-app)}
        tr[data-c=doc] td.ic svg{color:var(--c-doc)}
        tr[data-c=code] td.ic svg{color:var(--c-code)}
        td.nm{overflow-wrap:anywhere;min-width:0}
        td.nm a{display:block;padding:6px 0;font-size:14.5px}
        td.nm a:hover{color:var(--accent)}
        td.sz,td.dt{font-size:13px;color:var(--muted);white-space:nowrap;
          font-variant-numeric:tabular-nums}
        td.ac{width:1%;white-space:nowrap;text-align:right;padding-left:0}
        /* ★ padding:0 不能省:.act 里既有 <a> 也有 <button>,而上面通用的
           `button{padding:7px 14px}` 会照样命中——29px 的方块里塞 28px 内边距,
           图标被挤成 1px 宽,表现为"改名/删除按钮凭空消失,只有下载(<a> 版)还在"。 */
        .act{display:inline-flex;align-items:center;justify-content:center;
          width:29px;height:29px;padding:0;border-radius:8px;color:var(--muted);
          border:0;background:none;cursor:pointer;opacity:.55;
          transition:opacity .12s,background .12s,color .12s}
        .act svg{flex:none}
        tr:hover .act,tr:focus-within .act{opacity:1}
        .act:hover{background:var(--bg);color:var(--accent)}
        .act.del:hover{color:var(--danger)}
        .act svg{width:16px;height:16px;fill:currentColor}
        input[type=checkbox]{width:16px;height:16px;accent-color:var(--accent);cursor:pointer}
        tr.up td{color:var(--muted)}
        tr.up td.ic svg{color:var(--muted)}
        #empty{padding:52px 20px;text-align:center;color:var(--muted);font-size:14px}

        /* 预览浮层 */
        #lb{position:fixed;inset:0;z-index:60;display:none;
          background:rgba(8,10,14,.86);backdrop-filter:blur(3px);
          align-items:center;justify-content:center;padding:28px}
        #lb.on{display:flex}
        #lbbody{max-width:100%;max-height:100%;display:flex;align-items:center;
          justify-content:center;flex-direction:column;gap:14px}
        #lbbody img,#lbbody video{max-width:min(1080px,92vw);max-height:82vh;
          border-radius:10px;background:#000}
        #lbbody audio{width:min(560px,88vw)}
        #lbname{color:#e9ecf1;font-size:13px;word-break:break-all;text-align:center;
          max-width:min(1080px,92vw)}
        #lbx{position:absolute;top:16px;right:18px;width:40px;height:40px;border-radius:12px;
          border:0;background:rgba(255,255,255,.12);color:#fff;display:flex;
          align-items:center;justify-content:center;cursor:pointer}
        #lbx:hover{background:rgba(255,255,255,.22)}
        #lbx svg{width:20px;height:20px;fill:currentColor}

        /* 提示条 */
        #toast{position:fixed;left:50%;bottom:26px;transform:translate(-50%,80px);
          z-index:80;max-width:min(560px,88vw);padding:11px 18px;border-radius:11px;
          background:#22262e;color:#f2f4f7;font-size:13.5px;box-shadow:0 8px 24px rgba(0,0,0,.28);
          opacity:0;transition:transform .22s,opacity .22s;word-break:break-all}
        #toast.on{transform:translate(-50%,0);opacity:1}
        #toast.bad{background:var(--danger)}

        @media(max-width:640px){
          .hd,.crumbs{padding-left:14px;padding-right:14px}
          main{padding:14px 14px 40px}
          td.dt,th.dt{display:none}
          .act{opacity:1}
          tbody td{height:52px}
        }
    """.trimIndent()

    /** 排序 + 筛选 + 预览浮层 + 提示条。只读共享也有这些,所以放在公共部分。 */
    val JS_COMMON = """
        function ${'$'}(s){return document.querySelector(s);}
        function ${'$'}${'$'}(s){return [].slice.call(document.querySelectorAll(s));}
        var tb=${'$'}('#t tbody'),rows=${'$'}${'$'}('#t tbody tr:not(.up)'),filter=${'$'}('#filter');
        var toastEl=${'$'}('#toast'),toastT=null;
        function toast(msg,bad){toastEl.textContent=msg;toastEl.className='on'+(bad?' bad':'');
          if(toastT)clearTimeout(toastT);toastT=setTimeout(function(){toastEl.className='';},3200);}
        function refilter(){var q=filter.value.toLowerCase(),n=0;
          rows.forEach(function(r){var ok=!q||r.getAttribute('data-name').indexOf(q)>=0;
            r.hidden=!ok;if(ok)n++;});
          var e=${'$'}('#empty');if(e)e.hidden=n>0;}
        filter.addEventListener('input',refilter);
        var sdir=1,skey=null;
        ${'$'}${'$'}('th.s').forEach(function(th){
          th.onclick=function(){var k=th.getAttribute('data-k');
            sdir=(k===skey)?-sdir:1;skey=k;
            ${'$'}${'$'}('th.s').forEach(function(o){o.removeAttribute('data-d');});
            th.setAttribute('data-d',sdir>0?'a':'d');
            rows.sort(function(a,b){
              var ad=+a.getAttribute('data-dir'),bd=+b.getAttribute('data-dir');
              if(ad!==bd)return bd-ad;
              if(k==='name'){var x=a.getAttribute('data-name'),y=b.getAttribute('data-name');
                return x<y?-sdir:x>y?sdir:0;}
              return (+a.getAttribute('data-'+k) - +b.getAttribute('data-'+k))*sdir;});
            rows.forEach(function(r){tb.appendChild(r);});};});
        var lb=${'$'}('#lb'),lbBody=${'$'}('#lbbody'),lbName=${'$'}('#lbname');
        function closeLb(){lb.className='';lbBody.innerHTML='';}
        ${'$'}('#lbx').onclick=closeLb;
        lb.addEventListener('click',function(e){if(e.target===lb)closeLb();});
        document.addEventListener('keydown',function(e){if(e.key==='Escape')closeLb();});
        document.addEventListener('click',function(e){
          if(!e.target.closest)return;
          var a=e.target.closest('a[data-prev]');if(!a)return;
          e.preventDefault();
          var kind=a.getAttribute('data-prev'),url=a.getAttribute('href'),el;
          if(kind==='image'){el=document.createElement('img');el.src=url;}
          else{el=document.createElement(kind==='video'?'video':'audio');
            el.src=url;el.controls=true;el.autoplay=true;}
          lbBody.appendChild(el);
          lbName.textContent=a.textContent;lbBody.appendChild(lbName);
          lb.className='on';});
    """.trimIndent()
}
