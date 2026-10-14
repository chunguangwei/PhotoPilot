import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'assets'))
from render_asset_primitives import *
from render_icon_assets import icon, brand

MATRIX = {
 'Nav/NavItem': ['normal','pressed','focused','selected','alert-badge','rail-collapsed'],
 'Button/Primary': ['normal','pressed','focused','disabled','loading'],
 'Button/Secondary': ['normal','pressed','focused','disabled','danger'],
 'Input/Segmented': ['normal','pressed','focused','disabled','selected','three-wrap','four-overflow'],
 'Input/Slider': ['normal','pressed','focused','disabled','readout','input','no-transition'],
 'Input/Switch': ['normal','pressed','focused','disabled','selected','on','off','on-disabled','off-disabled'],
 'Media/PhotoCell': ['normal','pressed','focused','selected','error','not-downloaded','downloading','corrupt-retry'],
 'Bar/SelectionConfirm': ['normal','focused','disabled','zero-ready','24-selected-20-ready'],
 'List/SourceRow': ['normal','pressed','focused','disabled','error','syncing','auth-expired','partial-access'],
 'List/TaskRow': ['normal','focused','error','wait-network','wait-space','paused','partial-failure','unknown-size'],
 'State/Empty': ['normal','focused','no-frames','no-results','no-permission'],
 'Modal/DangerConfirm': ['normal','pressed','focused','disabled','scope-title','multi-frame','keep-copies'],
 'Player/GlassBar': ['normal','pressed','focused','disabled','selected','glass','solid','visible','hidden'],
 'Player/Overlay': ['normal','error','weather-off','offline','toolbar-avoidance']
}
BASE = ['normal','pressed','focused','disabled','selected','error']
ZH_STATES = {'normal':'默认','pressed':'按下','focused':'键盘 / 辅助焦点','disabled':'不可用','selected':'已选择','alert-badge':'异常徽标','rail-collapsed':'折叠导航','loading':'载入中 · 防重复','danger':'危险动作','three-wrap':'三项 · 换行','four-overflow':'四项 · 重排','readout':'独立读数','input':'可直接输入','no-transition':'无转场 · 禁用','on':'开启','off':'关闭','on-disabled':'开启且禁用','off-disabled':'关闭且禁用','error':'错误','not-downloaded':'未下载','downloading':'下载中','corrupt-retry':'损坏 · 重试','zero-ready':'零张可播','24-selected-20-ready':'24 已选 / 20 可播','syncing':'同步中','auth-expired':'认证失效','partial-access':'部分可访问','wait-network':'等网络','wait-space':'等空间','paused':'已暂停','partial-failure':'部分失败','unknown-size':'未知大小','no-frames':'无相框','no-results':'无结果','no-permission':'无权限','scope-title':'作用域标题','multi-frame':'多个相框引用','keep-copies':'保留副本','glass':'透明控制层','solid':'减少透明度 · 实底','visible':'显示','hidden':'隐藏','weather-off':'天气关闭','offline':'离线','toolbar-avoidance':'工具条显示 · 避让'}


def tr(zh,en,lang):
    return zh if lang=='zh' else en


def button(s,x,y,w,label,state='normal',factor=1,secondary=False,danger=False):
    h=max(52,round(44*factor))
    disabled=state in ('disabled','loading')
    fill='separator' if disabled else 'status.error' if danger and not secondary else 'surface.panel' if secondary else 'text.primary'
    fg='text.secondary' if disabled else 'status.error' if danger and secondary else 'text.primary' if secondary else 'surface.canvas'
    if state=='pressed':
        fill='accent.action' if not danger else 'status.error'
        fg='surface.canvas'
    s.rect(x,y,w,h,fill,12 if s.platform=='ios' else 26,'text.secondary' if secondary else None)
    if state=='pressed' and danger:
        s.rect(x+5,y+5,w-10,h-10,None,8 if s.platform=='ios' else 22,'surface.canvas',2,name='pressed-danger-inset')
    if state=='focused':
        s.rect(x-6,y-6,w+12,h+12,None,18 if s.platform=='ios' else 32,'accent.action',3,name='focus-ring')
    if state=='loading':
        s.path(f'M{x+24} {y+16} C{x+10} {y+16} {x+10} {y+38} {x+24} {y+38}',fg,2,name='loading-indicator')
    inset=42 if state=='loading' else 18
    s.text(x+inset,y+(h-17*factor)/2,label,'body',fg,factor,w-inset-16)
    return h


def focus(s,x,y,w,h):
    s.rect(x-6,y-6,w+12,h+12,None,18,'accent.action',3,name='focus-ring-not-selection')


def progress(s,x,y,w,value=None):
    s.rect(x,y,w,6,'separator',3)
    s.rect(x+(w*.28 if value is None else 0),y,w*(.24 if value is None else value),6,'accent.action',3,name='indeterminate-no-percentage' if value is None else 'progress')


def paint(s,comp,state,x,y,w,lang,f):
    t=lambda zh,en:tr(zh,en,lang)
    body=lambda dx,dy,zh,en,color='text.primary':s.wrap(x+dx,y+dy,t(zh,en),w-dx-20,'body',color,f)
    cap=lambda dx,dy,zh,en,color='text.secondary':s.wrap(x+dx,y+dy,t(zh,en),w-dx-20,'caption',color,f)
    disabled=state=='disabled'
    if comp=='Nav/NavItem':
        narrow=state=='rail-collapsed'
        ww=80 if narrow else w
        r=12 if s.platform=='ios' else 30
        h=max(64,48*f)
        s.rect(x,y,ww,h,'separator' if state=='pressed' else 'surface.panel',r)
        if state=='selected':
            if s.platform=='ios':
                s.rect(x,y,4,h,'accent.action',2)
            else:
                s.rect(x+8,y+8,52,h-16,'separator',24)
        icon(s,'nav_frames',x+24,y+(h-24)/2,24,'accent.action' if state=='selected' else 'text.primary')
        if not narrow:
            s.text(x+72,y+(h-17*f)/2,t('相框','Frames'),'body',factor=f)
        if state=='selected':
            icon(s,'check',x+w-44,y+20,24,'accent.action')
        if state=='alert-badge':
            icon(s,'warning',x+w-44,y+20,24,'status.warning')
            cap(0,108,'来源需要处理，本地照片仍可播放','Source needs attention. Local photos remain playable.')
        if state=='focused':
            focus(s,x,y,ww,h)
        if narrow:
            cap(0,108,'保留可访问名称：相框','Accessible name retained: Frames')
    elif comp.startswith('Button/'):
        label=t('正在保存','Saving') if state=='loading' else t('从本机删除','Delete from device') if state=='danger' else t('加入相框','Add to frame') if comp.endswith('Primary') else t('取消','Cancel')
        button(s,x,y,w,label,state,f,comp.endswith('Secondary'),state=='danger')
        if state=='loading':
            cap(0,108,'保存过程中阻止重复提交','Repeated submission is blocked while saving.')
        elif disabled:
            cap(0,108,'先选择至少一张照片','Select at least one photo first.')
    elif comp=='Input/Segmented':
        wrap=state in ('three-wrap','four-overflow')
        labels=[t('完整展示','Full photo'),t('纯色补边','Solid edges'),t('裁剪铺满','Fill crop')] if state=='three-wrap' else [t('来源','Sources'),t('拍摄时间','Capture date'),t('照片方向','Orientation'),t('下载状态','Download state')] if state=='four-overflow' else [t('目录','Folders'),t('照片','Photos')]
        cols=2 if len(labels)==4 or not wrap else 1
        cw=(w-12*(cols-1))/cols
        ch=60 if f==1 else 80
        active=1 if state=='selected' else 0
        for i,label in enumerate(labels):
            xx=x+(i%cols)*(cw+12);yy=y+(i//cols)*(ch+12)
            selected=i==active
            fill='separator' if disabled or (state=='pressed' and i==1) else 'surface.panel'
            s.rect(xx,yy,cw,ch,fill,10 if s.platform=='ios' else 24,'accent.action' if selected and not disabled else 'separator',2 if selected else 1)
            s.wrap(xx+16,yy+12,label,cw-56,'body','text.secondary' if disabled else 'text.primary',f)
            if selected and not disabled:
                icon(s,'check',xx+cw-34,yy+18,20,'accent.action')
            if state=='focused' and i==1:
                focus(s,xx,yy,cw,ch)
        if wrap:
            cap(0,290,'完整标签重排，不横向裁掉选项','Labels reflow; no option is clipped horizontally.')
    elif comp=='Input/Slider':
        dis=state in ('disabled','no-transition')
        body(0,0,'转场时长','Transition duration')
        cy=y+104
        s.path(f'M{x+12} {cy} H{x+w-20}','separator',4)
        s.path(f'M{x+12} {cy} H{x+w*.42}','text.secondary' if dis else 'accent.action',4)
        if state=='pressed':
            s.dot(x+w*.42,cy,24,'accent.action',name='pressed-thumb-halo')
        s.dot(x+w*.42,cy,12,'text.secondary' if dis else 'text.primary')
        if state=='focused':
            s.dot(x+w*.42,cy,22,None,'accent.action',3,name='focus-ring')
        s.rect(x,y+156,180,64,'surface.panel',12,'accent.action' if state=='input' else 'separator',2)
        s.text(x+18,y+172,'0.8 s','heading','text.secondary' if dis else 'text.primary',f)
        if state=='input':
            s.path(f'M{x+140} {y+169} V{y+205}','accent.action',2,name='input-caret')
        cap(0,258,'无转场时不可调整；停留为独立参数' if dis else '两张照片之间的过渡时间','Disabled for None; photo duration is separate.' if dis else 'Time spent changing between two photos.')
    elif comp=='Input/Switch':
        on=state in ('selected','on','on-disabled')
        dis=state in ('disabled','on-disabled','off-disabled')
        body(0,0,'天气','Weather')
        yy=y+90
        tw=64 if s.platform=='ios' else 60
        th=36 if s.platform=='ios' else 32
        s.rect(x,yy,tw,th,'separator' if dis else 'accent.action' if on else 'text.secondary',th/2)
        cx=x+tw-18 if on else x+18
        s.dot(cx,yy+th/2,14,'surface.panel')
        if on:
            icon(s,'check',cx-10,yy+th/2-10,20,'text.secondary' if dis else 'accent.action')
        if state=='pressed':
            s.dot(cx,yy+th/2,24,None,'accent.action',3,name='pressed-halo')
        if state=='focused':
            focus(s,x-4,yy-8,tw+8,th+16)
        s.text(x+100,yy,t('开','On') if on else t('关','Off'),'body','text.secondary' if dis else 'text.primary',f)
        cap(0,188,'开关默认关闭；禁用态保留当前值','Default is off. Disabled state preserves its current value.')
    elif comp=='Media/PhotoCell':
        h=224
        s.rect(x,y,w,h,'surface.panel',12,'accent.action' if state=='selected' else 'separator',3 if state=='selected' else 1)
        if state in ('error','corrupt-retry'):
            icon(s,'warning',x+24,y+24,32,'status.error')
            body(24,78,'无法读取照片','Cannot read this photo','status.error')
            button(s,x+24,y+142,w-48,t('重试','Retry'),factor=f,secondary=True)
        elif state in ('not-downloaded','downloading'):
            icon(s,'download',x+24,y+32,32)
            body(24,100,'下载中 · 40%' if state=='downloading' else '尚未下载','Downloading · 40%' if state=='downloading' else 'Not downloaded')
            progress(s,x+24,y+180,w-48,.4 if state=='downloading' else 0)
        else:
            s.path(f'M{x+28} {y+166} C{x+100} {y+96} {x+160} {y+100} {x+238} {y+162} C{x+330} {y+118} {x+410} {y+142} {x+w-28} {y+156}','text.secondary',2,name='original-geometric-photo-proxy')
            s.dot(x+100,y+66,18,'accent.brand')
        s.dot(x+w-32,y+32,16,'accent.action' if state=='selected' else 'surface.canvas','text.primary',2,name='selection-target-visual')
        if state=='selected':
            icon(s,'check',x+w-44,y+20,24,'surface.canvas')
        if state=='pressed':
            s.rect(x+8,y+8,w-16,h-16,None,8,'text.primary',3,name='pressed-inset')
        if state=='focused':
            focus(s,x,y,w,h)
        cap(0,250,'示意几何图；导入不等于展示。下载条不覆盖勾选区','Geometric proxy. Import does not mean display. Progress stays clear of selection.')
    elif comp=='Bar/SelectionConfirm':
        zero=state in ('disabled','zero-ready')
        s.rect(x,y,w,290,'surface.panel',16)
        body(20,20,'已选 24 张','24 photos selected')
        cap(20,82,'0 张可播 · 24 张下载中' if zero else '20 张可播 · 4 张下载中','0 ready · 24 downloading' if zero else '20 ready · 4 downloading')
        button(s,x+20,y+192,w-40,t('开始播放','Start playback'),'disabled' if zero else 'focused' if state=='focused' else 'normal',f)
    elif comp=='List/SourceRow':
        s.rect(x,y,w,336,'surface.panel',12)
        icon(s,'nav_sources',x+20,y+20,32,'text.secondary' if disabled else 'text.primary')
        s.text(x+76,y+20,t('家庭 NAS','Family NAS'),'heading','text.secondary' if disabled else 'text.primary',f,w-100)
        if state=='pressed':
            s.rect(x+4,y+4,w-8,328,None,10,'accent.action',3)
        if state=='focused':
            focus(s,x,y,w,336)
        messages={'error':('连接中断；已导入照片保留','Connection lost; imported photos kept'),'syncing':('正在同步所选目录','Syncing selected folders'),'auth-expired':('认证已失效；本地照片保留','Sign-in expired; local photos kept'),'partial-access':('2 个目录可访问，1 个受限','2 folders accessible; 1 restricted'),'disabled':('来源已停用；保留本机副本','Source inactive; local copies kept')}
        zh,en=messages.get(state,('2 个目录 · 上次同步 10:20','2 folders · Last sync 10:20'))
        body(20,100,zh,en,'status.error' if state in ('error','auth-expired') else 'status.warning' if state=='partial-access' else 'text.secondary')
        if state=='syncing':
            progress(s,x+20,y+254,w-40,None)
        elif state in ('error','auth-expired','partial-access'):
            button(s,x+20,y+242,w-40,t('重新认证','Sign in again') if state=='auth-expired' else t('检查并重试','Check and retry'),factor=f,secondary=True)
    elif comp=='List/TaskRow':
        body(0,0,'导入照片','Import photos')
        msgs={'normal':('已导入 12 / 30 · 10 张可播','12 / 30 imported · 10 ready'),'error':('导入失败；已导入照片保留','Import failed; imported photos kept'),'wait-network':('等待网络；已导入照片保留','Waiting for network; photos kept'),'wait-space':('等待空间；清理缓存后继续','Waiting for storage; clear cache'),'paused':('已暂停；保留当前进度','Paused; progress is preserved'),'partial-failure':('28 已导入 · 2 失败，可重试','28 imported · 2 failed; retry available'),'unknown-size':('下载中 · 大小未知','Downloading · Size unknown')}
        zh,en=msgs.get(state,msgs['normal'])
        body(0,70,zh,en,'status.error' if state=='error' else 'status.warning' if state in ('wait-network','wait-space','partial-failure') else 'text.secondary')
        progress(s,x,y+182,w,None if state=='unknown-size' else .4)
        if state in ('error','partial-failure','paused','wait-space'):
            button(s,x,y+244,w,t('继续','Resume') if state=='paused' else t('管理存储','Manage storage') if state=='wait-space' else t('重试失败项','Retry failed items'),factor=f,secondary=True)
        if state=='focused':
            button(s,x,y+244,w,t('暂停','Pause'),'focused',f,True)
    elif comp=='State/Empty':
        symbol='search' if state=='no-results' else 'lock' if state=='no-permission' else 'empty'
        icon(s,symbol,x,y,32,'accent.action')
        body(0,62,'没有搜索结果' if state=='no-results' else '需要照片权限' if state=='no-permission' else '还没有相框','No results' if state=='no-results' else 'Photo access needed' if state=='no-permission' else 'No frames yet')
        cap(0,128,'清除筛选后重试' if state=='no-results' else '仅在你授权后读取所选照片' if state=='no-permission' else '导入后，再选择要展示的照片','Clear filters and try again.' if state=='no-results' else 'Only selected photos are read after permission.' if state=='no-permission' else 'Import first, then choose what to display.')
        button(s,x,y+250,w,t('清除筛选','Clear filters') if state=='no-results' else t('查看权限设置','Review permissions') if state=='no-permission' else t('创建相框','Create frame'),'focused' if state=='focused' else 'normal',f)
    elif comp=='Modal/DangerConfirm':
        keep=state=='keep-copies'
        s.rect(x,y,w,484,'surface.panel',24 if s.platform=='ios' else 28)
        if s.platform=='ios':
            s.rect(x+w/2-24,y+12,48,4,'separator',2,name='sheet-handle')
        title=t('断开来源？','Disconnect source?') if keep else t('从本机删除 24 张照片？','Delete 24 photos from this device?')
        used=s.wrap(x+24,y+40,title,w-48,'heading',factor=f)
        msg=t('保留已导入的本地照片；仅清除连接凭证。','Keep imported photos on this device; remove connection credentials only.') if keep else t('影响 2 个相框。源端文件不会删除。','Affects 2 frames. Source files will not be deleted.')
        s.wrap(x+24,y+56+used,msg,w-48,'body','text.secondary',f)
        if keep:
            icon(s,'check',x+24,y+258,24,'accent.action')
            s.wrap(x+64,y+252,t('保留本机副本','Keep local copies'),w-90,'body',factor=f)
        button(s,x+24,y+322,w-48,t('断开来源','Disconnect source') if keep else t('从本机删除','Delete from device'),state if state in ('pressed','focused','disabled') else 'normal',f,danger=True)
        button(s,x+24,y+406,w-48,t('取消','Cancel'),factor=f,secondary=True)
    elif comp=='Player/GlassBar':
        s.rect(x,y,w,318,'separator',12)
        s.path(f'M{x+12} {y+160} L{x+w/2} {y+80} L{x+w-12} {y+170}','text.secondary',2)
        if state!='hidden':
            bar_y=y+210
            s.rect(x+16,bar_y,w-32,80,'surface.panel',(40 if s.platform=='ios' else 28),opacity=1 if state=='solid' else .94,name='solid-fallback' if state=='solid' else 'static-tint-not-native-blur')
            for i,name in enumerate(('play_previous','play_pause','play_next','play_more')):
                xx=x+44+i*(w-88)/4
                active=i==1
                if active and state in ('selected','pressed'):
                    s.rect(xx-8,bar_y+12,56,56,'accent.action' if state=='selected' else 'separator',28)
                if active and state=='focused':
                    focus(s,xx-8,bar_y+12,56,56)
                icon(s,'play' if state=='selected' and active else name,xx+8,bar_y+28,24,'text.secondary' if disabled else 'surface.canvas' if active and state=='selected' else 'text.primary')
            if state=='focused':
                cap(0,348,'辅助焦点驻留，不自动隐藏','Do not auto-hide while accessibility focus remains.')
        else:
            cap(0,348,'照片保留；单击后显示控制层','Photo remains. Tap to show controls.')
        if state=='selected':
            cap(0,348,'已暂停；主控制变为播放','Paused; primary control changes to Play.')
    elif comp=='Player/Overlay':
        s.rect(x,y,w,180 if state=='weather-off' else 354,'surface.panel',16)
        avoid=state=='toolbar-avoidance'
        yy=y+(24 if avoid or state=='weather-off' else 112)
        s.text(x+24,yy,'09:41','clock',factor=f)
        if state!='weather-off':
            msg=t('天气不可用','Weather unavailable') if state=='error' else t('离线 · 上次更新 09:20','Offline · Updated 09:20') if state=='offline' else t('晴 · 22°C','Sunny · 22°C')
            s.wrap(x+24,yy+64*f+20,msg,w-48,'body','status.error' if state=='error' else 'text.secondary',f)
        if avoid:
            s.rect(x+24,y+274,w-48,56,'separator',28)
            for i,name in enumerate(('play_previous','play_pause','play_next','play_more')):
                icon(s,name,x+52+i*(w-104)/4,y+290,24)
        cap(0,382,'天气为示例；关闭天气时不预留空位','Weather is illustrative; no empty weather slot when off.')


def main():
    variants=[]
    issues=[]
    for comp,states in MATRIX.items():
        for platform in ('ios','android'):
            for lang in ('zh','en'):
                for theme in ('light','dark'):
                    for scale in (1,1.6):
                        cellw=640
                        cellh=630 if comp=='Modal/DangerConfirm' else 540
                        rows=math.ceil(len(states)/2)
                        w,h=1400,150+rows*cellh+84
                        s=Scene(w,h,theme,platform,scale=2)
                        s.rect(0,0,w,h,'surface.canvas')
                        s.text(40,28,comp,'title')
                        s.text(40,74,f'{platform} / {lang} / {theme} / '+('1.6x design stress — NOT OS maximum' if scale>1 else '1.0x type / design candidate'),'body')
                        cells=[]
                        for i,state in enumerate(states):
                            x=40+(i%2)*690;y=142+(i//2)*cellh
                            s.text(x,y,ZH_STATES[state] if lang=='zh' else state,'caption','accent.action')
                            s.rect(x,y+36,cellw,cellh-62,None,16,'separator')
                            start=len(s.items)
                            paint(s,comp,state,x+24,y+68,cellw-48,lang,scale)
                            cells.append({'state':state,'bounds':[x,y,cellw,cellh-24],'visual_layers':len(s.items)-start})
                        s.text(40,h-64,tr('静态设计候选 · 触点 iOS ≥44 / Android ≥48 · Android 字体需原生复核','Static design · Targets iOS ≥44 / Android ≥48 · Android native fonts pending',lang),'caption')
                        s.text(40,h-34,tr('焦点环不代表选中；系统弹窗语言由系统控制','Focus does not mean selected; system dialogs follow OS language.',lang),'meta')
                        name=comp.lower().replace('/','-')+f'-{platform}-{lang}-{theme}--'+('large-type' if scale>1 else 'regular')
                        paths=s.save(EXPORT/'components'/name)
                        variants.append({'component':comp,'platform':platform,'language':lang,'appearance':theme,'type_scale':scale,'states':states,'cells':cells,'formats':paths,'width':w,'height':h})
                        issues.extend({'board':name,**e} for e in s.issues)
        print('Rendered',comp,flush=True)
    manifest={'version':'1.1','date':DATE,'tokens_source':'02 §3.1, parsed directly at generation','tokens':TOKENS,'type_source':'02 §3.2','states_source':'12 §2','matrix':{c:{'meaningful':states,'not_applicable':{st:'Not meaningful per 12 §2; intentionally not fabricated.' for st in BASE if st not in states}} for c,states in MATRIX.items()},'variant_dimensions':{'platform':['ios','android'],'language':['zh','en'],'appearance':['light','dark'],'type_scale':[1,1.6]},'platform_differences':['iOS row selection indicator vs Android pill','iOS sheet handle vs Android dialog','iOS and Android switch track geometry','44pt/48dp minimum target specification; design coordinates not device calibration'],'glass':'Tint alpha .94 is a static candidate; native blur/Liquid Glass not simulated or validated. Solid state has identical control layout.','limitations':LIMITS,'boards':variants,'text_width_issues':issues,'board_count':len(variants),'state_cell_count':sum(len(v['states']) for v in variants)}
    dump(EXPORT/'components/components-manifest.json',manifest)
    print(json.dumps({'boards':len(variants),'cells':manifest['state_cell_count'],'text_width_issues':len(issues)}))
    if issues:
        print(json.dumps(issues[:20],ensure_ascii=False,indent=2))

if __name__=='__main__':
    main()
