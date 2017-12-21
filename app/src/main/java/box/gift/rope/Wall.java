package box.gift.rope;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import box.shoe.gameutils.Entity;
import box.shoe.gameutils.Paintable;
import box.shoe.gameutils.Vector;

/**
 * Created by Joseph on 12/21/2017.
 */

public class Wall extends Entity implements Paintable
{
    private Paint paint;

    public Wall(double initialX, double initialY, double initialWidth, double initialHeight)
    {
        super(initialX, initialY, initialWidth, initialHeight, new Vector(-10, 0));
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.RED);
    }

    @Override
    public void paint(int x, int y, Canvas canvas)
    {
        canvas.drawRect(x, y, x + (float) width, y + (float) width, paint);
    }
}