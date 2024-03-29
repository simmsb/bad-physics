package somephysicsthing.solarsystem;

import somephysicsthing.solarsystem.bounded.Bounded;
import somephysicsthing.solarsystem.propertytraits.*;
import somephysicsthing.solarsystem.quadtree.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MassSimulation<T extends MutPosition & MutVelocity & HasMass> {
    @Nonnull private final List<T> elems;
    private final double width, height;
    private final double theta;
    private final double g;

    MassSimulation(@Nonnull List<T> elems, double width, double height) {
        this.elems = elems;
        this.width = width;
        this.height = height;
        this.theta = 1.2;
        // this.g = 6.67e-11;
        this.g = 1e-6f;
    }

    MassSimulation(@Nonnull List<T> elems, double width, double height, double theta, double g) {
        this.elems = elems;
        this.width = width;
        this.height = height;
        this.theta = theta;
        this.g = g;
    }

    HashMap<List<Direction>, Bounded> runSimulation(double ts) {
        var tree = new Quadtree<T>(this.width, this.height);

        for (var elem : this.elems) {
            tree.insert(elem);
        }

        HashMap<List<Direction>, MassyPoint> weights = (new CalculateWeights()).calculate(tree);

        this.elems.parallelStream()
                .forEach(elem -> {
                    var massesToUse = tree.getPathsFitting((Bounded region) -> {
                        var avgWidth = (region.getH() + region.getW()) / 2.0f;
                        var dist = region.getPos().sub(elem.getPos()).abs();

                        return (avgWidth / dist) < this.theta;
                    });

                    var massesStream = massesToUse
                            .parallelStream()
                            .map(weights::get)
                            .filter(Objects::nonNull);

                    var newValues = this.calcValuesFromGravity(elem, massesStream, ts);

                    elem.setPosition(newValues.left);
                    elem.setVelocity(newValues.right);
                });

        return tree.getRegions();
    }

    @Nonnull
    private Vec2 calcAccelForPoint(@Nonnull Vec2 pos, double mass, @Nonnull MassyPoint p2) {
        var dist = p2.pos.sub(pos);

        var distSqrt = Math.sqrt(dist.abs());
        if (distSqrt < 1)
            distSqrt = 1;

        var accel = (this.g * p2.mass) / distSqrt;

        return dist.normal().scale(accel);
    }

    @Nonnull
    private Vec2 calcAccelInner(@Nonnull Vec2 pos, double mass, @Nonnull ArrayList<MassyPoint> points) {


        var accel = points.parallelStream()
                .map((p) -> this.calcAccelForPoint(pos, mass, p))
                .reduce(Vec2::add)
                .orElseGet(() -> new Vec2(0, 0));

        if (Double.isNaN(accel.x) || Double.isNaN(accel.y)) {
            return new Vec2(0, 0);
        }

        return accel;
    }

    /**
     * calculate the new position and velocity for a particle
     * @param p1 the point to focus on for calculating force
     * @param pointsStream the points affecting it
     * @param ts the timestep
     * @return a 2-tuple of new pos, new velocity
     */
    @Nonnull private Tuple2<Vec2, Vec2> calcValuesFromGravity(@Nonnull T p1, @Nonnull Stream<MassyPoint> pointsStream, double ts) {
        var pos = p1.getPos();
        var vel = p1.getVelocity();
        var mass = p1.getMass();

        var points = pointsStream
                .filter((p) -> p.linkedElement != p1)
                .collect(Collectors.toCollection(ArrayList::new));

        // perform runge kutta
        var k1dx = this.calcAccelInner(pos, mass, points);
        var k2dx = this.calcAccelInner(pos.add(vel.scale(ts / 2)), mass, points);
        var k2x = vel.add(vel).scale(ts / 2);
        var k3dx = this.calcAccelInner(pos.add(k2x.scale(ts / 2)), mass, points);
        var k3x = vel.add(k2dx).scale(ts / 2);
        var k4dx = this.calcAccelInner(pos.add(k3x.scale(ts)), mass, points);
        var k4x = vel.add(k3dx).scale(ts);

        var dx = vel.add(k1dx.add(k2dx.scale(2)).add(k3dx.scale(2)).add(k4dx).scale(ts / 6));
        var d =  pos.add(vel.add(k2x.scale(2)).add(k3x.scale(2)).add(k4x).scale(ts / 6));

        return new Tuple2<>(d, dx);
    }

    private class MassyPoint implements HasPosition, HasMass {
        @Nonnull private final Vec2 pos;
        private final double mass;

        @Nullable final T linkedElement;

        public MassyPoint(@Nonnull Vec2 pos, double mass) {
            this.pos = pos;
            this.mass = mass;
            this.linkedElement = null;
        }

        /**
         * represents either an object with mass or the centre of mass of set of objects
         * @param pos position of the point
         * @param mass mass of the point
         * @param linkedElement if the massy point is linked to an element this is non null
         */
        MassyPoint(@Nonnull Vec2 pos, double mass, @Nullable T linkedElement) {
            this.pos = pos;
            this.mass = mass;
            this.linkedElement = linkedElement;
        }

        @Nonnull
        @Override
        public Vec2 getPos() {
            return this.pos;
        }

        @Override
        public double getMass() {
            return this.mass;
        }

        @Nonnull
        MassyPoint merge(@Nonnull MassyPoint other) {
            var combinedMass = this.mass + other.mass;

            if (combinedMass == 0.0f) {
                // special case when both masses are zero
                return new MassyPoint(this.pos.add(other.pos).scale(0.5f), 0);
            }

            var pos = this.pos.scale(this.mass).add(other.pos.scale(other.mass)).scale(1.0f / combinedMass);

            return new MassyPoint(pos, combinedMass);
        }

        @Override
        public String toString() {
            return "MassyPoint{" +
                    "pos=" + this.pos +
                    ", mass=" + this.mass +
                    '}';
        }
    }

    class CalculateWeights implements QuadtreeFolder<T, MassyPoint> {
        private HashMap<List<Direction>, MassyPoint> centresOfMass;

        CalculateWeights() {
            this.centresOfMass = new HashMap<>();
        }

        @Nonnull HashMap<List<Direction>, MassyPoint> calculate(@Nonnull Quadtree<T> tree) {
            tree.applyFold(this);
            return this.centresOfMass;
        }

        @Nonnull
        @Override
        public MassyPoint visitEmpty(@Nonnull List<Direction> path) {
            // nodes with no elements clearly have no mass
            return new MassyPoint(new Vec2(0, 0), 0);
        }

        @Nonnull
        @Override
        public MassyPoint visitLeaf(@Nonnull List<Direction> path, @Nonnull T elem) {
            var massPoint = new MassyPoint(elem.getPos(), elem.getMass(), elem);
            this.centresOfMass.put(path, massPoint);
            return massPoint;
        }

        @Nonnull
        @Override
        public MassyPoint visitQuad(@Nonnull List<Direction> path, @Nonnull MassyPoint nw, @Nonnull MassyPoint ne, @Nonnull MassyPoint sw, @Nonnull MassyPoint se) {
            var massPoint = nw.merge(ne).merge(sw).merge(se);
            this.centresOfMass.put(path, massPoint);
            return massPoint;
        }
    }
}
