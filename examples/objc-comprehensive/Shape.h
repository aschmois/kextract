#import <Foundation/Foundation.h>

/**
 * Bitfield options for how a shape is rendered.
 * Generated as @JvmInline value class (NS_OPTIONS style).
 */
typedef NS_OPTIONS(NSInteger, ShapeOptions) {
    ShapeOptionNone     = 0,
    ShapeOptionFilled   = 1,
    ShapeOptionBordered = 2,
    ShapeOptionShadow   = 4,
};

/**
 * Discriminant identifying the concrete shape type.
 * Generated as enum class (NS_ENUM style).
 */
typedef NS_ENUM(NSInteger, ShapeKind) {
    ShapeKindUnknown   = 0,
    ShapeKindRectangle = 1,
    ShapeKindCircle    = 2,
};

/** Protocol implemented by any drawable shape. */
@protocol Drawable
- (NSString *)draw;
@end

/**
 * Abstract 2-D shape base class: width, height, area, describe.
 */
@interface Shape : NSObject
@property (nonatomic, readonly) double width;
@property (nonatomic, readonly) double height;
+ (instancetype)shapeWithWidth:(double)w height:(double)h;
- (double)area;
- (NSString *)describe;
@end

/**
 * Category adding perimeter() to Shape.
 * Exercised both on a plain Shape and on a Rectangle subtype.
 */
@interface Shape (Geometry)
- (double)perimeter;
@end

/**
 * Rectangle inherits Shape and implements Drawable.
 * Demonstrates ObjC single inheritance through kextract bindings.
 */
@interface Rectangle : Shape <Drawable>
+ (instancetype)rectWithWidth:(double)w height:(double)h;
- (NSString *)draw;
@end
