#import "Shape.h"

// Private readwrite extension so the factory methods can set width/height.
@interface Shape ()
@property (nonatomic, readwrite) double width;
@property (nonatomic, readwrite) double height;
@end

// ── Shape ────────────────────────────────────────────────────────────────────

@implementation Shape

+ (instancetype)shapeWithWidth:(double)w height:(double)h {
    Shape *s = [[Shape alloc] init];
    s.width  = w;
    s.height = h;
    return s;
}

- (double)area {
    return self.width * self.height;
}

- (NSString *)describe {
    return [NSString stringWithFormat:@"Shape(%.1f x %.1f)", self.width, self.height];
}

@end

// ── Shape (Geometry) — category ───────────────────────────────────────────────

@implementation Shape (Geometry)

- (double)perimeter {
    return 2.0 * (self.width + self.height);
}

@end

// ── Rectangle ────────────────────────────────────────────────────────────────

@implementation Rectangle

+ (instancetype)rectWithWidth:(double)w height:(double)h {
    Rectangle *r = [[Rectangle alloc] init];
    r.width  = w;
    r.height = h;
    return r;
}

- (NSString *)draw {
    return [NSString stringWithFormat:@"Rectangle(%.1f x %.1f)", self.width, self.height];
}

@end
