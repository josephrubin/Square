package box.shoe.gameutils;

import android.support.annotation.NonNull;

import org.jetbrains.annotations.Contract;

/**
 * Created by Joseph on 10/21/2017.
 * Represents a vector in the mathematical sense.
 * A magnitude and a direction, described by x and y length values.
 * This class is IMMUTABLE.
 */

public final class Vector implements Interpolatable<Vector>
{
    private final double x;
    private final double y;

    public static final Vector ZERO = new Vector(0, 0);

    /**
     * Constructs a new Vector
     * @param x the x length
     * @param y the y length
     */
    public Vector(final double x, final double y)
    {
        this.x = x;
        this.y = y;
    }

    /**
     * Calculates the magnitude (length) of this Vector
     * @return the Vector's magnitude
     */
    public double magnitude()
    {
        return Math.sqrt((x * x) + (y * y));
    }

    /**
     * Constructs a Vector in the same direction as this one, but with a magnitude of 1.
     * @return this Vector's unit Vector.
     */
    public Vector unit()
    {
        double magnitude = magnitude();
        if (magnitude == 0)
        {
            throw new ArithmeticException("Vector magnitude is 0. Cannot create unit vector.");
        }
        return this.scale(1 / magnitude);
    }

    /**
     * Adds another Vector to this one.
     * @param otherVector the Vector to add to this one.
     * @return a Vector representing the addition of the two supplied Vectors.
     */
    @NonNull
    public Vector add(@NonNull Vector otherVector)
    {
        return new Vector(x + otherVector.getX(), y + otherVector.getY());
    }

    /**
     * Subtracts another Vector from this Vector
     * @param otherVector the Vector to subtract.
     * @return a Vector representing the subtraction of the other Vector from this one.
     */
    @NonNull
    public Vector subtract(@NonNull Vector otherVector)
    {
        return new Vector(x - otherVector.getX(), y - otherVector.getY());
    }

    /**
     * Scales this vector's magnitude by a factor.
     * @param factor the factor to scale by.
     * @return a new, scaled Vector.
     */
    @NonNull
    public Vector scale(double factor)
    {
        return new Vector(x * factor, y * factor);
    }

    /**
     * Takes the dot product of two Vectors.
     * @param otherVector the Vector to multiply with.
     * @return the dot product of the two Vectors.
     */
    @Contract(pure = true)
    public double dot(@NonNull Vector otherVector)
    {
        return (x * otherVector.x) + (y * otherVector.y);
    }

    /**
     * Projects this Vector onto another Vector.
     * @param target the Vector to project onto.
     * @return the Vector projection.
     */
    @NonNull
    public Vector projectOnto(@NonNull Vector target)
    {
        return target.unit().scale(this.dot(target.unit()));
    }

    /**
     * Creates a new Vector with only the x value of this one, and y set to 0.
     * @return the horizontal Vector.
     */
    @NonNull
    public Vector onlyX()
    {
        return new Vector(x, 0);
    }

    /**
     * Creates a new Vector with only the y value of this one, and x set to 0.
     * @return the vertical Vector.
     */
    @NonNull
    public Vector onlyY()
    {
        return new Vector(0, y);
    }

    /**
     * Rotates this Vector by a number of radians to create a new Vector.
     * @param radians the degree in radians to rotate by.
     * @return the rotated Vector.
     */
    @NonNull
    public Vector rotateBy(double radians)
    {
        return new Vector(x * Math.cos(radians) - y * Math.sin(radians), x * Math.sin(radians) + y * Math.cos(radians));
    }

    /**
     * Creates a Vector perpendicular to this one.
     * @return the perpendicular Vector
     */
    @NonNull
    public Vector perpendicular()
    {
        return this.rotateBy(Math.PI / 2);
    }

    @Contract(pure = true)
    public double getX()
    {
        return x;
    }

    @Contract(pure = true)
    public double getY()
    {
        return y;
    }

    @NonNull
    @Contract(pure = true)
    public String toString()
    {
        return "x: " + x + " y: " + y;
    }

    @NonNull
    @Override
    public Vector copy()
    {
        // We are immutable so no need to copy....
        return this;
        //return new Vector(getX(), getY());
    }

    @NonNull
    @Override
    public Vector interpolateTo(Vector other, double interpolationRatio)
    {
        double newX = other.getX() * (interpolationRatio + 1) - (interpolationRatio * getX());
        double newY = other.getY() * (interpolationRatio + 1) - (interpolationRatio * getY());
        return new Vector(newX, newY);
    }
}
