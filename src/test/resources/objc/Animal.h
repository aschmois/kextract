#import <Foundation/Foundation.h>

@protocol Greetable <NSObject>
- (NSString *)greet;
@optional
- (NSString *)greetWithName:(NSString *)name;
@end

@interface Animal : NSObject <Greetable>
@property (nonatomic, copy) NSString *name;
+ (instancetype)animalWithName:(NSString *)name;
- (NSString *)description;
@end

@interface Dog : Animal
- (void)bark;
- (NSString *)greet;
@end
