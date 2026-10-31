package com.kira.jujutsuneon.rbx;

/**
 * Поза по костям: поворот (кватернион в соглашении GeoBone), смещение (пиксели), масштаб.
 * Флаги — какие каналы у кости заданы (кость без ключей не трогается, как в Roblox).
 */
public final class Pose {
    public static final int ROT = 1, POS = 2, SCALE = 4;

    public double[][] rot = new double[0][];
    public double[][] pos = new double[0][];
    public double[][] scale = new double[0][];
    public int[] mask = new int[0];

    public void ensure(int n) {
        if (mask.length >= n) return;
        int old = mask.length;
        double[][] r = new double[n][], p = new double[n][], s = new double[n][];
        System.arraycopy(rot, 0, r, 0, old);
        System.arraycopy(pos, 0, p, 0, old);
        System.arraycopy(scale, 0, s, 0, old);
        for (int i = old; i < n; i++) {
            r[i] = new double[]{0, 0, 0, 1};
            p[i] = new double[3];
            s[i] = new double[]{1, 1, 1};
        }
        int[] m = new int[n];
        System.arraycopy(mask, 0, m, 0, old);
        rot = r;
        pos = p;
        scale = s;
        mask = m;
    }

    public void clear() {
        ensure(Bones.count());
        for (int i = 0; i < mask.length; i++) {
            mask[i] = 0;
            QMath.identity(rot[i]);
            pos[i][0] = pos[i][1] = pos[i][2] = 0;
            scale[i][0] = scale[i][1] = scale[i][2] = 1;
        }
    }

    public boolean has(int bone, int channel) {
        return bone < mask.length && (mask[bone] & channel) != 0;
    }
}
