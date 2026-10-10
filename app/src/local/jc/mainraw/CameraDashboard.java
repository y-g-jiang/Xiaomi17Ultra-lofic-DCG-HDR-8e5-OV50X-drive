package local.jc.mainraw;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Native, density-aware camera surface. No synthetic camera or histogram data. */
final class CameraDashboard {
    static final int BG=0xff0c1016,CARD=0xff171e28,TEXT=0xffedf2f7,MUTED=0xff94a3b8,ACCENT=0xff79e4c7;
    final Activity a;final LinearLayout root;
    TextureView preview;TextView status,telemetry,rawLabel;HistogramView histogram;
    EditText exposure;Switch infinity,ettr,unity,auto;
    Button capture,ptc,advanced,recover,export,rootCheck,previewButton;
    private int dp(float n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    CameraDashboard(Activity a) {
        this.a=a;a.getWindow().setStatusBarColor(BG);a.getWindow().setNavigationBarColor(BG);
        a.getWindow().getDecorView().setSystemUiVisibility(0);
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout screen=new LinearLayout(a);screen.setOrientation(1);screen.setBackgroundColor(BG);
        root=new LinearLayout(a);root.setOrientation(1);root.setPadding(dp(18),dp(12),dp(18),dp(20));scroll.addView(root);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout dock=new LinearLayout(a);dock.setOrientation(1);dock.setPadding(dp(18),dp(6),dp(18),dp(10));dock.setBackgroundColor(BG);screen.addView(dock);a.setContentView(screen);
        LinearLayout header=row(root);TextView title=text(header,"JC  /  RAW",27,TEXT);title.setTypeface(Typeface.create("sans-serif-medium",0));
        TextView iso=text(header,"ISO 50  ·  1×",13,ACCENT);iso.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);iso.setLayoutParams(new LinearLayout.LayoutParams(0,dp(48),1));
        text(root,"单帧双路 · 主摄",13,MUTED);
        FrameLayout viewport=new FrameLayout(a);viewport.setBackground(shape(0xff05070a,18));viewport.setClipToOutline(true);
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,dp(270));vp.setMargins(0,dp(14),0,dp(12));root.addView(viewport,vp);
        preview=new TextureView(a);viewport.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        Grid grid=new Grid(a);viewport.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        telemetry=new TextView(a);telemetry.setText("轻点开启预览");telemetry.setTextColor(TEXT);telemetry.setTextSize(12);telemetry.setPadding(dp(14),dp(10),dp(14),dp(10));telemetry.setBackgroundColor(0xaa0c1016);
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);viewport.addView(telemetry,tp);
        LinearLayout meter=card(root);text(meter,"预览亮度",14,TEXT);histogram=new HistogramView(a);meter.addView(histogram,new LinearLayout.LayoutParams(-1,dp(72)));
        rawLabel=text(meter,"LOFIC 实时源尚未接通",12,MUTED);
        LinearLayout controls=card(root);text(controls,"曝光与对焦",17,TEXT);
        auto=toggle(controls,"自动快门 · ISO 固定 50",true);
        LinearLayout exp=row(controls);text(exp,"快门  ms",14,MUTED);exposure=new EditText(a);exposure.setSingleLine();exposure.setInputType(8194);exposure.setText("3.75");exposure.setTextSize(18);exposure.setTextColor(TEXT);exposure.setBackgroundTintList(ColorStateList.valueOf(ACCENT));exp.addView(exposure,new LinearLayout.LayoutParams(0,dp(50),1));
        infinity=toggle(controls,"无限远锁定",true);unity=toggle(controls,"高光扩展 · LOFIC 1×",true);ettr=toggle(controls,"两张测光 · LOFIC Top10",false);
        status=text(dock,"准备就绪",12,MUTED);status.setMaxLines(3);status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v->new android.app.AlertDialog.Builder(a).setTitle("采集状态").setMessage(status.getText()).setPositiveButton("关闭",null).show());
        previewButton=button(dock,"开启实时预览",false);capture=button(dock,"拍摄  ·  保存原始 DNG",true);
        LinearLayout tools=card(root);text(tools,"采集工具",17,TEXT);
        ptc=button(tools,"PTC 自动配对扫描",false);advanced=button(tools,"高级模式与传感器参数",false);
        LinearLayout recovery=card(root);text(recovery,"会话与恢复",17,TEXT);
        export=button(recovery,"导出待处理拍摄",false);recover=button(recovery,"结束并恢复",false);rootCheck=button(recovery,"检查 Root 状态",false);
        text(root,"原始 DNG 保存在 下载 / JCCamera\n预览亮度用于取景；LOFIC 测光使用真实 RAW。",12,MUTED);
    }
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    LinearLayout row(LinearLayout parent){LinearLayout l=new LinearLayout(a);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);parent.addView(l,new LinearLayout.LayoutParams(-1,-2));return l;}
    LinearLayout card(LinearLayout parent){LinearLayout l=new LinearLayout(a);l.setOrientation(1);l.setPadding(dp(16),dp(12),dp(16),dp(12));l.setBackground(shape(CARD,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(9));parent.addView(l,p);return l;}
    TextView text(LinearLayout p,String s,int size,int color){TextView t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));p.addView(t);return t;}
    Switch toggle(LinearLayout p,String s,boolean on){Switch v=new Switch(a);v.setText(s);v.setTextSize(14);v.setTextColor(TEXT);v.setChecked(on);v.setThumbTintList(ColorStateList.valueOf(ACCENT));v.setPadding(0,dp(7),0,dp(7));p.addView(v,new LinearLayout.LayoutParams(-1,dp(48)));return v;}
    Button button(LinearLayout p,String s,boolean primary){Button b=new Button(a);b.setText(s);b.setAllCaps(false);b.setTextSize(primary?17:14);b.setTextColor(primary?BG:TEXT);b.setBackground(shape(primary?ACCENT:0xff263140,14));b.setStateListAnimator(null);LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(-1,dp(primary?58:47));q.setMargins(0,dp(5),0,dp(5));p.addView(b,q);return b;}
    static final class HistogramView extends View {
        private final Paint paint=new Paint(3);private int[] bins;HistogramView(Activity a){super(a);}
        void show(int[] h){bins=h==null?null:h.clone();invalidate();}
        protected void onDraw(Canvas c){super.onDraw(c);paint.setColor(0xff344052);c.drawLine(0,getHeight()-1,getWidth(),getHeight()-1,paint);if(bins==null)return;
            int max=1;for(int x:bins)max=Math.max(max,x);Path p=new Path();p.moveTo(0,getHeight());
            for(int i=0;i<bins.length;i++)p.lineTo(i*getWidth()/(float)(bins.length-1),getHeight()-2-(getHeight()-6)*bins[i]/(float)max);
            p.lineTo(getWidth(),getHeight());p.close();paint.setColor(0x9979e4c7);c.drawPath(p,paint);
        }
    }
    static final class Grid extends View {final Paint p=new Paint(3);Grid(Activity a){super(a);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        protected void onDraw(Canvas c){p.setColor(0x35ffffff);p.setStrokeWidth(1);for(int n=1;n<=2;n++){c.drawLine(getWidth()*n/3f,0,getWidth()*n/3f,getHeight(),p);c.drawLine(0,getHeight()*n/3f,getWidth(),getHeight()*n/3f,p);}}
    }
}
