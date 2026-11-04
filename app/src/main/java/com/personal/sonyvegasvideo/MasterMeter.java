package com.personal.sonyvegasvideo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** File peak envelope multiplied by preview gain, rather than a fabricated live level. */
public final class MasterMeter extends View {
    private final Paint p=new Paint(3);private float level;
    public MasterMeter(Context context){super(context);setContentDescription("Пиковый уровень файла при предпросмотре");setTooltipText("Пики декодированного файла × громкость предпросмотра");}
    public void signal(float value){level=Math.max(0,Math.min(1,value));invalidate();}
    @Override protected void onDraw(Canvas c){
        float d=getResources().getDisplayMetrics().density,h=getHeight()-14*d,top=6*d;
        p.setColor(EditorStyle.SURFACE);c.drawRect(0,0,getWidth(),getHeight(),p);
        float l=8*d,r=getWidth()-21*d;
        p.setColor(EditorStyle.PREVIEW);c.drawRect(l,top,r,h,p);
        float normalized=level>0?Math.max(0,(60+(float)(20*Math.log10(level)))/60):0;
        for(int i=0;i<30;i++){
            float yy=h-(h-top)*i/30f;
            p.setColor(i<normalized*30?(i>26?0xffd74e39:i>22?0xffeed17a:0xff75ceab):0xff34465d);
            c.drawRect(l+1,yy-(h-top)/30f+1,r-1,yy-1,p);
        }
        p.setColor(EditorStyle.SECONDARY);p.setTextSize(8*d);
        for(int i=0;i<=6;i++)c.drawText(i==0?"0":Integer.toString(-10*i),r+3*d,top+(h-top)*i/6+5*d,p);
        p.setTextSize(8*d);c.drawText("dB",l,getHeight()-2*d,p);
    }
}
