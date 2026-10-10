export function wheelDistance(delta:number,mode:number,height:number){return Math.max(-240,Math.min(240,delta*(mode===1?16:mode===2?height:1)));}
// Browser scrollY is integer-rounded. Snap before an eased step rounds to zero.
export function easeScroll(current:number,target:number){return Math.abs(target-current)<=2.5?target:current+(target-current)*.22;}
